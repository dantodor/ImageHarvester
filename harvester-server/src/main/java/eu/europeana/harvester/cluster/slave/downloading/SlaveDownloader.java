package eu.europeana.harvester.cluster.slave.downloading;

import akka.event.LoggingAdapter;
import com.ning.http.client.*;
import eu.europeana.harvester.cluster.domain.messages.RetrieveUrl;
import eu.europeana.harvester.domain.DocumentReferenceTaskType;
import eu.europeana.harvester.httpclient.response.HttpRetrieveResponse;
import eu.europeana.harvester.httpclient.response.ResponseState;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SlaveDownloader {

    private final LoggingAdapter LOG;

    public SlaveDownloader(final LoggingAdapter LOG) {
        this.LOG = LOG;
    }

    public void downloadAndStoreInHttpRetrievResponse(final HttpRetrieveResponse httpRetrieveResponse, final RetrieveUrl task) {

        if ((task.getDocumentReferenceTask().getTaskType() != DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD) &&
        (task.getDocumentReferenceTask().getTaskType() != DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD)) {
            throw new IllegalArgumentException("The downloader can handle only condition & unconditional downloads. Cannot handle "+task.getDocumentReferenceTask().getTaskType());
        }

        final AsyncHttpClient asyncHttpClient = new AsyncHttpClient(new AsyncHttpClientConfig.Builder()
                .setMaxRedirects(task.getHttpRetrieveConfig().getMaxNrOfRedirects())
                .setFollowRedirect(true)
                .setAcceptAnyCertificate(true)
                .setConnectTimeout(task.getHttpRetrieveConfig().getConnectionTimeoutInMillis())
                .setMaxRequestRetry(3)
                .build());
        httpRetrieveResponse.setState(ResponseState.PROCESSING);

        final long connectionSetupStartTimestamp = System.currentTimeMillis();

        final ListenableFuture<Integer> downloadListener = asyncHttpClient.prepareGet(task.getUrl()).execute(new AsyncHandler<Integer>() {
            final TimeWindowCounter timeWindowCounter = new TimeWindowCounter();

            @Override
            public STATE onStatusReceived(HttpResponseStatus status) throws Exception {
                final long connectionSetupDurationInMillis = System.currentTimeMillis() - connectionSetupStartTimestamp;
                httpRetrieveResponse.setCheckingDurationInMilliSecs(connectionSetupDurationInMillis);

                if (connectionSetupDurationInMillis > task.getHttpRetrieveConfig().getConnectionTimeoutInMillis()) {
                    /* Initial connection setup time longer than threshold. */
                    httpRetrieveResponse.setState(ResponseState.FINISHED_TIME_LIMIT);
                    httpRetrieveResponse.setLog("Download aborted as connection setup duration " + connectionSetupDurationInMillis + " ms was greater than maximum configured  " + task.getHttpRetrieveConfig().getConnectionTimeoutInMillis() + " ms");
                    return STATE.ABORT;
                }

                    /* We don't care what kind of status code it has at this moment as we will decide what to
                     * do on it only after the response headers have been received.
                     */
                httpRetrieveResponse.setHttpResponseCode(status.getStatusCode());

                return STATE.CONTINUE;
            }

            @Override
            public STATE onHeadersReceived(HttpResponseHeaders downloadResponseHeaders) throws Exception {
                final long downloadDurationInMillis = System.currentTimeMillis() - connectionSetupStartTimestamp;
                httpRetrieveResponse.setRetrievalDurationInMilliSecs(downloadDurationInMillis);

                    /* Collect the response headers */
                for (final Map.Entry<String, List<String>> entry : downloadResponseHeaders.getHeaders()) {
                    for (final String header : entry.getValue()) {
                        httpRetrieveResponse.addHttpResponseHeaders(entry.getKey(), header);
                    }
                }

                /** We terminate the connection in case of HTTP error only after we collect the response headers */
                if (httpRetrieveResponse.getHttpResponseCode() >= 400) {
                    httpRetrieveResponse.setState(ResponseState.ERROR);
                    httpRetrieveResponse.setLog("HTTP >=400 code received. Connection aborted after response headers collected.");
                    return STATE.ABORT;
                }

                /** Abort when conditional download and headers match */
                if (task.getDocumentReferenceTask().getTaskType() == DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD) {
                    final String existingContentLength = task.getHeaders().get("Content-Length");
                    final String downloadContentLength = downloadResponseHeaders.getHeaders().getFirstValue("Content-Length");

                    if (existingContentLength != null && downloadContentLength != null && existingContentLength.equalsIgnoreCase(downloadContentLength)) {
                        // Same content length response headers => abort
                        httpRetrieveResponse.setState(ResponseState.COMPLETED);
                        httpRetrieveResponse.setLog("Conditional download aborted as existing Content-Length == "+existingContentLength+" and download Content-Length == "+downloadContentLength);
                        return STATE.ABORT;
                    }
                }

                timeWindowCounter.start();

                return STATE.CONTINUE;
            }

            @Override
            public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {

                final long downloadDurationInMillis = System.currentTimeMillis() - connectionSetupStartTimestamp;
                httpRetrieveResponse.setRetrievalDurationInMilliSecs(downloadDurationInMillis);

                if (downloadDurationInMillis > task.getHttpRetrieveConfig().getTerminationThresholdTimeLimit().getMillis()) {
                    /* Download duration longer than threshold. */
                    httpRetrieveResponse.setState(ResponseState.FINISHED_TIME_LIMIT);
                    httpRetrieveResponse.setLog("Download aborted as it's duration " + downloadDurationInMillis + " ms was greater than maximum configured  " + task.getHttpRetrieveConfig().getTerminationThresholdTimeLimit().getMillis() + " ms");
                    return STATE.ABORT;
                }

                if (timeWindowCounter.countsPerSecond() < task.getHttpRetrieveConfig().getTerminationThresholdReadPerSecondInBytes()) {
                        /* Abort early if download is throttled by the sender. */
                    httpRetrieveResponse.setState(ResponseState.FINISHED_RATE_LIMIT);
                    httpRetrieveResponse.setLog("Download aborted as it was throttled by the sender to " + timeWindowCounter.countsPerSecond() + " bytes during the last " + timeWindowCounter.getTimeWindowSizeInSeconds() + ". This was greater than the minimum configured  " + task.getHttpRetrieveConfig().getTerminationThresholdReadPerSecondInBytes() + "  bytes / sec");
                    return STATE.ABORT;
                }

                timeWindowCounter.incrementCount(bodyPart.length());
                httpRetrieveResponse.addContent(bodyPart.getBodyPartBytes());
                return STATE.CONTINUE;

            }

            @Override
            public Integer onCompleted() throws Exception {
                LOG.debug("Finished download");
                httpRetrieveResponse.setState(ResponseState.COMPLETED);
                httpRetrieveResponse.setRetrievalDurationInMilliSecs(System.currentTimeMillis() - connectionSetupStartTimestamp);
                try {
                    httpRetrieveResponse.close();
                } catch (IOException e1) {
                    LOG.info("Failed to close the response, caused by : " + e1.getMessage());
                }
                return 0;
            }

            @Override
            public void onThrowable(Throwable e) {

                // Check if the tim threshold limit was exceeded & save that information.
                final long downloadDurationInMillis = System.currentTimeMillis() - connectionSetupStartTimestamp;
                if (downloadDurationInMillis > task.getHttpRetrieveConfig().getTerminationThresholdTimeLimit().getMillis()) {
                    /* Download duration longer than threshold. */
                    httpRetrieveResponse.setState(ResponseState.FINISHED_TIME_LIMIT);
                    httpRetrieveResponse.setLog("Download aborted as it's duration " + downloadDurationInMillis + " ms was greater than maximum configured  " + task.getHttpRetrieveConfig().getTerminationThresholdTimeLimit().getMillis() + " ms");
                }


                // Check if it was aborted because of conditional download with with same headers.
                if (httpRetrieveResponse.getState() == ResponseState.COMPLETED && task.getDocumentReferenceTask().getTaskType() == DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD) {
                    // We don't set any exception as the download was aborted for a legitimate reason.
                    cleanup(httpRetrieveResponse, asyncHttpClient, httpRetrieveResponse.getException());
                }
                else {
                    // We set the exception as the download was aborted because of a problem.
                    cleanup(httpRetrieveResponse, asyncHttpClient, e);
                }
            }
        });

        try {

            Integer r = downloadListener.get(task.getHttpRetrieveConfig().getTerminationThresholdTimeLimit().getStandardSeconds(), TimeUnit.SECONDS);
            LOG.debug("Download finished with status {}", r);

        } catch (Exception e) {
            cleanup(httpRetrieveResponse, asyncHttpClient, e);
        } finally {
            cleanup(httpRetrieveResponse, asyncHttpClient, httpRetrieveResponse.getException());
        }
    }

    private void cleanup(final HttpRetrieveResponse httpRetrieveResponse, final AsyncHttpClient asyncHttpClient, final Throwable e) {
        if (httpRetrieveResponse != null) httpRetrieveResponse.setException(e);
        try {
            if (httpRetrieveResponse != null) httpRetrieveResponse.close();
        } catch (IOException e1) {
            LOG.error("Failed to close the response, caused by : " + e1.getMessage());
        }

        if (asyncHttpClient != null) asyncHttpClient.close();
    }

}
