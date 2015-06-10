package eu.europeana.publisher.dao;

import eu.europeana.publisher.domain.CRFSolrDocument;
import eu.europeana.publisher.domain.RetrievedDocument;
import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.BinaryRequestWriter;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonParams;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singletonMap;

/**
 * Writes additional fields/properties to SOLR
 */
public class SOLRWriter {

    /**
     * The maximum number of ID's that can be present in a SOLR search query.
     * Important because of the limitations of the HTTP URL length.
     */
    private static final int MAX_NUMBER_OF_IDS_IN_SOLR_QUERY = 100;

    private static final int MAX_RETRIES = 5;
    private static final Logger LOG = LogManager.getLogger(SOLRWriter.class.getName());

    private final String solrUrl;

    public SOLRWriter (String url) {
        if (null == url || url.trim().isEmpty()) {
            throw new IllegalArgumentException("Solr Url cannot be null");
        }

        this.solrUrl = url;
    }

    private SolrClient createServer () {
        final HttpSolrClient server = new HttpSolrClient(solrUrl);
        server.setRequestWriter(new BinaryRequestWriter());
        return server;
    }

    /**
     * Updates a list of documents with new fields/properties
     *
     * @param newDocs the list of documents and the new fields
     */
    public boolean updateDocuments (List<CRFSolrDocument> newDocs) throws IOException{
        if (null == newDocs || newDocs.isEmpty()) {
            return false;
        }

        final List<SolrInputDocument> docsToUpdate = new ArrayList<SolrInputDocument>();

        int retry = 0;
        while (retry <= MAX_RETRIES) {
            final SolrClient server = createServer();

            // Adding individual documents in server
            for (final CRFSolrDocument CRFSolrDocument : newDocs) {

                final SolrInputDocument update = new SolrInputDocument();


                update.addField("europeana_id", CRFSolrDocument.getRecordId());

                update.addField("is_fulltext", singletonMap("set", CRFSolrDocument.getIsFulltext()));

                update.addField("has_thumbnails", singletonMap("set", CRFSolrDocument.getHasThumbnails()));

                update.addField("has_media", singletonMap("set", CRFSolrDocument.getHasMedia()));

                update.addField("filter_tags", singletonMap("set", CRFSolrDocument.getFilterTags()));

                update.addField("facet_tags", singletonMap("set", CRFSolrDocument.getFacetTags()));

                try {
                    final UpdateResponse response = server.add(update);
                    LOG.info(response.toString());
                } catch (Exception e) {
                    LOG.error("SOLR: exception when adding specific document " + update.toString() + " => document skipped",

                              e);
                }
            }

            try {
                LOG.info("SOLR: added " + newDocs.size() + " documents with commit - retry" + retry);
                server.commit();
                server.close();
                return true;
            } catch (Exception e) {
                LOG.error("Got exception while committing added documents", e);
                server.close();
                if (retry >= MAX_RETRIES) {
                    LOG.error("Reached maximum number of retries. Skipping record set with size=" + docsToUpdate.size());
                    return false;
                }
                else {
                    try {
                        retry++;
                        final long secsToSleep = retry * 10;
                        LOG.error("Exception with SOLR ...." + e.getMessage() + " retries executed already " + retry + " => sleeping " + secsToSleep + " s and retrying");
                        TimeUnit.SECONDS.sleep(secsToSleep);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks existence of documents in the SOLR index.
     *
     * @param documents the document that need to be checked
     * @return the map that indicates for each document id (map key) whether exists : true or false
     */
    public List<RetrievedDocument> filterDocumentIds (final List<RetrievedDocument> documents) {

        if (null == documents || documents.isEmpty()) {
            return Collections.EMPTY_LIST;
        }


        final Set<String> acceptedRecordIds = new HashSet<>();
        final List<String> documentIds = new ArrayList<>();

        for (final RetrievedDocument document: documents) {
            documentIds.add(document.getDocumentStatistic().getRecordId());
        }

        // As the SOLR query has limitations it cannot handle queries that are too large => we need to break them in parts
        for (int documentIdsStartChunkIndex = 0; documentIdsStartChunkIndex <= documentIds.size(); documentIdsStartChunkIndex += MAX_NUMBER_OF_IDS_IN_SOLR_QUERY) {
            final int endOfArray = (documentIdsStartChunkIndex + MAX_NUMBER_OF_IDS_IN_SOLR_QUERY >= documentIds.size()) ? documentIds
                                                                                                                                  .size() : documentIdsStartChunkIndex + MAX_NUMBER_OF_IDS_IN_SOLR_QUERY;
            final List<String> documentIdsToQuery = documentIds.subList(documentIdsStartChunkIndex, endOfArray);

            if (!documentIdsToQuery.isEmpty()) {
                // Do the SOLR query
                final SolrQuery query = new SolrQuery();
                final String queryString = "(" + StringUtils.join(documentIdsToQuery, " OR ").replace("/", "\\/") + ")";
                query.set(CommonParams.Q, "*:*");
                query.set(CommonParams.ROWS, MAX_NUMBER_OF_IDS_IN_SOLR_QUERY + 1);
                query.set(CommonParams.FQ, "europeana_id:" + queryString);
                query.set(CommonParams.FL, "europeana_id");

                try {
                    final SolrClient server = createServer();

                    final QueryResponse response = server.query(query);
                    // Mark in the result the documents id's that have been found
                    if (response != null) {
                        final SolrDocumentList solrResults = response.getResults();
                        for (int resultEntryIndex = 0; resultEntryIndex < solrResults.size(); ++resultEntryIndex)
                            acceptedRecordIds.add(solrResults.get(resultEntryIndex).getFieldValue("europeana_id").toString());
                    }
                    server.close();
                } catch (Exception e) {
                    LOG.error("SOLR query failed when executing existence query " + queryString + " => will mark the " +
                                      "docs as non-existing");
                    LOG.error(e);
                }
            }
        }

        final Iterator<RetrievedDocument> documentIterator = documents.iterator();

        while (documentIterator.hasNext()) {
            final RetrievedDocument document = documentIterator.next();

            if (!acceptedRecordIds.contains(document.getDocumentStatistic().getRecordId())) {
                documentIterator.remove();
            }
        }

        return documents;

    }
}
