package eu.europeana.harvester.cluster.slave.downloading;

import eu.europeana.harvester.cluster.domain.messages.RetrieveUrl;
import eu.europeana.harvester.cluster.slave.HttpServer;
import eu.europeana.harvester.domain.*;
import eu.europeana.harvester.httpclient.response.HttpRetrieveResponse;
import eu.europeana.harvester.httpclient.response.HttpRetrieveResponseFactory;
import eu.europeana.harvester.httpclient.response.RetrievingState;
import eu.europeana.harvester.httpclient.response.ResponseType;
import org.apache.logging.log4j.LogManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

import static eu.europeana.harvester.TestUtils.PATH_DOWNLOADED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import eu.europeana.harvester.TestUtils;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public class SlaveLinkCheckerTest {

    private static org.apache.logging.log4j.Logger LOG = LogManager.getLogger(SlaveDownloaderTest.class.getName());
    private static final String text1GitHubUrl = TestUtils.GitHubUrl_PREFIX + TestUtils.Image1;
    private final static int port = 9090;
    private static HttpServer server;
    private static final String assetDir = "./assets";
    final HttpRetrieveResponseFactory httpRetrieveResponseFactory = new HttpRetrieveResponseFactory();


    @Before
    public void setUp()  {
        try {
            server = new HttpServer(port);
            HttpServer.VirtualHost host = server.getVirtualHost(null); // default host
            host.setAllowGeneratedIndex(true); // with directory index pages
            File dir = new File(assetDir);
            host.addContext("/", new HttpServer.FileContextHandler(dir));
            server.start();
            System.out.println("HttpServer is listening on port " + port);
        } catch (Exception e) {
            System.err.println("error: " + e);
        }
    }

    @After
    public void tearDown() {
        server.stop();
    }
    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            System.out.println("Starting test: " + description.getMethodName());
        }
    };

    @Test
    public void canLinkCheckWithDefaultLimits() throws Exception {
        final SlaveLinkChecker slaveLinkChecker = new SlaveLinkChecker();
        final HttpRetrieveResponse response = httpRetrieveResponseFactory.create(ResponseType.NO_STORAGE,null);
        final RetrieveUrl task = new RetrieveUrl(text1GitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.CHECK_LINK,
                "referenceid-1","a", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.CHECK_LINK,
                        "source-reference-1", Collections.<ProcessingJobSubTask>emptyList()), null,new ReferenceOwner("unknown","unknwon","unknown"));

        slaveLinkChecker.downloadAndStoreInHttpRetrievResponse(response, task);

        assertEquals(RetrievingState.COMPLETED, response.getState());

        assertNotNull(response.getSourceIp());
        assertTrue(response.getSocketConnectToDownloadStartDurationInMilliSecs() > 0);
        assertTrue(response.getCheckingDurationInMilliSecs() > 0);
        assertTrue(response.getRetrievalDurationInMilliSecs() > 0);
    }

    @Test
    public void canLinkCheckWithDefaultLimits1() throws Exception {
        final SlaveLinkChecker slaveLinkChecker = new SlaveLinkChecker();
        final HttpRetrieveResponse response = httpRetrieveResponseFactory.create(ResponseType.NO_STORAGE,null);
        final ProcessingJobLimits limits = new ProcessingJobLimits(
                100*1000l /* retrievalTerminationThresholdTimeLimitInMillis  */,
                5 * 1000l /* retrievalTerminationThresholdReadPerSecondInBytes */,
                0l /* retrievalConnectionTimeoutInMillis - IT SHOULD FAIL BECAUSE OF THIS  */,
                10 /* retrievalMaxNrOfRedirects */,
                100 * 1000l /* processingTerminationThresholdTimeLimitInMillis */);

        final RetrieveUrl task = new RetrieveUrl(text1GitHubUrl, limits,DocumentReferenceTaskType.CHECK_LINK, "jobid-1",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.CHECK_LINK,
                        "source-reference-1", Collections.<ProcessingJobSubTask>emptyList()), null,new ReferenceOwner("unknown","unknwon","unknown"));

        slaveLinkChecker.downloadAndStoreInHttpRetrievResponse(response, task);

        assertEquals(RetrievingState.FINISHED_TIME_LIMIT, response.getState());

        assertNotNull(response.getSourceIp());
        assertTrue(response.getSocketConnectToDownloadStartDurationInMilliSecs() > 0);
        assertTrue(response.getCheckingDurationInMilliSecs() > 0);
        assertEquals(response.getRetrievalDurationInMilliSecs().longValue(), 0);
    }

    @Test
    public void canFailLinkCheckWhenUrlIsNonExistentWithDefaultLimits() throws Exception {
        final SlaveLinkChecker slaveLinkChecker = new SlaveLinkChecker();
        final HttpRetrieveResponse response = httpRetrieveResponseFactory.create(ResponseType.NO_STORAGE,null);
        final RetrieveUrl task = new RetrieveUrl(text1GitHubUrl+"-some-extra-nonsense", new ProcessingJobLimits(), DocumentReferenceTaskType.CHECK_LINK, "jobid-1",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.CHECK_LINK,
                        "source-reference-1", Collections.<ProcessingJobSubTask>emptyList()), null,new ReferenceOwner("unknown","unknwon","unknown"));

        slaveLinkChecker.downloadAndStoreInHttpRetrievResponse(response, task);

        assertEquals(RetrievingState.ERROR, response.getState());
        assertEquals(404, response.getHttpResponseCode().longValue());

        assertNotNull(response.getSourceIp());
        assertTrue(response.getSocketConnectToDownloadStartDurationInMilliSecs() > 0);
        assertTrue(response.getCheckingDurationInMilliSecs() > 0);
        assertTrue(response.getRetrievalDurationInMilliSecs() > 0);
    }


}
