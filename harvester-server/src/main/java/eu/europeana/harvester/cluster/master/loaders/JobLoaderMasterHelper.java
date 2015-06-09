package eu.europeana.harvester.cluster.master.loaders;

import akka.actor.ActorRef;
import akka.event.LoggingAdapter;
import akka.pattern.Patterns;
import akka.util.Timeout;
import eu.europeana.harvester.cluster.domain.ClusterMasterConfig;
import eu.europeana.harvester.cluster.domain.TaskState;
import eu.europeana.harvester.cluster.domain.messages.RetrieveUrl;
import eu.europeana.harvester.cluster.domain.messages.inner.*;
import eu.europeana.harvester.cluster.domain.utils.Pair;
import eu.europeana.harvester.db.MachineResourceReferenceDao;
import eu.europeana.harvester.db.ProcessingJobDao;
import eu.europeana.harvester.db.SourceDocumentProcessingStatisticsDao;
import eu.europeana.harvester.db.SourceDocumentReferenceDao;
import eu.europeana.harvester.domain.*;
import scala.concurrent.Await;
import scala.concurrent.Future;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class JobLoaderMasterHelper  {


    public static Map<String, Integer> getIPDistribution( MachineResourceReferenceDao machineResourceReferenceDao, LoggingAdapter LOG ) {
        LOG.info("Trying to load the IP distribution...");
        Page pg = new Page(0, 100000);
        List<MachineResourceReference> machines = machineResourceReferenceDao.getAllMachineResourceReferences(pg);
        Map<String, Integer> ipDistribution = new HashMap<>();

        for (MachineResourceReference machine : machines)
            ipDistribution.put(machine.getIp(), 0);

        LOG.info("IP distribution: ");
        for (Map.Entry<String, Integer> ip : ipDistribution.entrySet()) {
            LOG.info("{}: {}", ip.getKey(), ip.getValue());
            //machineResourceReferenceDao.createOrModify(new MachineResourceReference(ip.getKey()), WriteConcern.NORMAL);
        }

        LOG.info("Nr. of machines: {}", ipDistribution.size());
        LOG.info("End of IP distribution");
        return ipDistribution;
    }

    /**
     * Updates the list of jobs.
     */
    public static void updateLists(ClusterMasterConfig clusterMasterConfig, ProcessingJobDao processingJobDao,
                             SourceDocumentProcessingStatisticsDao sourceDocumentProcessingStatisticsDao,
                             SourceDocumentReferenceDao sourceDocumentReferenceDao,
                             ActorRef accountantActor, LoggingAdapter LOG) {
        try {
            checkForPausedJobs(clusterMasterConfig, processingJobDao, accountantActor, LOG);
            checkForResumedJobs(clusterMasterConfig, processingJobDao, sourceDocumentReferenceDao, sourceDocumentProcessingStatisticsDao, accountantActor, LOG);
        } catch (Exception e) {
            LOG.error(e.getMessage());
        }
    }



    /**
     * Checks if any job was stopped by a client.
     */
    private static void checkForPausedJobs( ClusterMasterConfig clusterMasterConfig, ProcessingJobDao processingJobDao, ActorRef accountantActor,
                                     LoggingAdapter LOG  ) {
        LOG.info("========= Looking for paused jobs from MongoDB ========");
        final Page page = new Page(0, clusterMasterConfig.getJobsPerIP());
        final List<ProcessingJob> all = processingJobDao.getJobsWithState(JobState.PAUSE, page);

        for (final ProcessingJob job : all) {

            final ProcessingJob newProcessingJob = job.withState(JobState.PAUSED);
            processingJobDao.update(newProcessingJob, clusterMasterConfig.getWriteConcern());

            accountantActor.tell(new PauseTasks(job.getId()), ActorRef.noSender());
        }
    }

    /**
     * Checks if any job was started by a client.
     */
    private static void checkForResumedJobs(ClusterMasterConfig clusterMasterConfig, ProcessingJobDao processingJobDao,
                                     SourceDocumentReferenceDao sourceDocumentReferenceDao, SourceDocumentProcessingStatisticsDao sourceDocumentProcessingDao,
                                     ActorRef accountantActor, LoggingAdapter LOG) {
        LOG.info("======== Looking for resumed jobs from MongoDB ========");
        final Page page = new Page(0, clusterMasterConfig.getJobsPerIP());
        final List<ProcessingJob> all = processingJobDao.getJobsWithState(JobState.RESUME, page);

        final List<String> resourceIds = new ArrayList<>();
        for (final ProcessingJob job : all) {
            for (final ProcessingJobTaskDocumentReference task : job.getTasks()) {
                final String resourceId = task.getSourceDocumentReferenceID();
                resourceIds.add(resourceId);
            }
        }
        final Map<String, SourceDocumentReference> resources = new HashMap<>();
        if (resourceIds.size() != 0) {
            final List<SourceDocumentReference> sourceDocumentReferences = sourceDocumentReferenceDao.read(resourceIds);
            for (SourceDocumentReference sourceDocumentReference : sourceDocumentReferences) {
                resources.put(sourceDocumentReference.getId(), sourceDocumentReference);
            }
        }

        for (final ProcessingJob job : all) {

            final ProcessingJob newProcessingJob = job.withState(JobState.RUNNING);
            processingJobDao.update(newProcessingJob, clusterMasterConfig.getWriteConcern());

            final Timeout timeout = new Timeout(scala.concurrent.duration.Duration.create(10, TimeUnit.SECONDS));
            final Future<Object> future = Patterns.ask(accountantActor, new IsJobLoaded(job.getId()), timeout);
            Boolean isLoaded = false;
            try {
                isLoaded = (Boolean) Await.result(future, timeout.duration());
            } catch (Exception e) {
                LOG.error("Error in checkForResumedJobs->IsJobLoaded: {}", e);
            }

            if (isLoaded) {
                accountantActor.tell(new ResumeTasks(job.getId()), ActorRef.noSender());
            } else {
                addJob(job, resources, processingJobDao, sourceDocumentProcessingDao,clusterMasterConfig, accountantActor, LOG );
            }
        }
    }

    /**
     * Adds a job and its tasks to our evidence.
     *
     * @param job the ProcessingJob object
     */
    private static void addJob(final ProcessingJob job, final Map<String, SourceDocumentReference> resources,
                        ProcessingJobDao processingJobDao, SourceDocumentProcessingStatisticsDao sourceDocumentProcessingDao,
                        ClusterMasterConfig clusterMasterConfig, ActorRef accountantActor, LoggingAdapter LOG ) {
        final List<ProcessingJobTaskDocumentReference> tasks = job.getTasks();

        final ProcessingJob newProcessingJob = job.withState(JobState.RUNNING);
        processingJobDao.update(newProcessingJob, clusterMasterConfig.getWriteConcern());

        final List<String> taskIDs = new ArrayList<>();
        for (final ProcessingJobTaskDocumentReference task : tasks) {
            final String ID = processTask(job, task, resources, sourceDocumentProcessingDao, accountantActor, LOG);
            if (ID != null) {
                taskIDs.add(ID);
            }
        }

        if (tasks.size() > 10)
            LOG.info("Loaded {} tasks for jobID {} on IP {}", tasks.size(), job.getId(), job.getIpAddress());

        accountantActor.tell(new AddTasksToJob(job.getId(), taskIDs), ActorRef.noSender() );
    }

    /**
     * Loads the task and all needed resources for that task.
     *
     * @param job  the job which contains the task
     * @param task the concrete task to load
     * @return generated task ID
     */
    private static String processTask(final ProcessingJob job, final ProcessingJobTaskDocumentReference task,
                               final Map<String, SourceDocumentReference> resources, SourceDocumentProcessingStatisticsDao sourceDocumentProcessingDao,
                               ActorRef accountantActor, LoggingAdapter LOG ) {
        final String sourceDocId = task.getSourceDocumentReferenceID();

        final SourceDocumentReference sourceDocumentReference = resources.get(sourceDocId);
        if (sourceDocumentReference == null) {
            return null;
        }

        final String ipAddress = job.getIpAddress();

        final RetrieveUrl retrieveUrl = new RetrieveUrl(sourceDocumentReference.getUrl(), job.getLimits(), task.getTaskType(),
                job.getId(), task.getSourceDocumentReferenceID(),
                getHeaders(task.getTaskType(), sourceDocumentReference, sourceDocumentProcessingDao ), task, ipAddress);

        List<String> tasksFromIP = null;
        final Timeout timeout = new Timeout(scala.concurrent.duration.Duration.create(10, TimeUnit.SECONDS));
        final Future<Object> future = Patterns.ask(accountantActor, new GetTasksFromIP(ipAddress), timeout);
        try {
            tasksFromIP = (List<String>) Await.result(future, timeout.duration());
        } catch (Exception e) {
            LOG.error("Error in processTask->GetTasksFromIP: {}", e);
        }

        if (tasksFromIP == null) {
            tasksFromIP = new ArrayList<>();
        } else {
            if (tasksFromIP.contains(retrieveUrl.getId())) {
                return null;
            }
        }
        tasksFromIP.add(retrieveUrl.getId());


        accountantActor.tell(new AddTasksToIP(ipAddress, tasksFromIP), ActorRef.noSender());
        accountantActor.tell(new AddTask(retrieveUrl.getId(), new Pair<>(retrieveUrl, TaskState.READY)), ActorRef.noSender() );

        return retrieveUrl.getId();
    }

    /**
     * Returns the headers of a source document if we already retrieved that at least once.
     *
     * @param documentReferenceTaskType task type
     * @param newDoc                    source document object
     * @return list of headers
     */
    private static Map<String, String> getHeaders(final DocumentReferenceTaskType documentReferenceTaskType,
                                           final SourceDocumentReference newDoc,
                                           final SourceDocumentProcessingStatisticsDao sourceDocumentProcessingStatisticsDao ) {
        Map<String, String> headers = null;

        if (documentReferenceTaskType == null) {
            return null;
        }

        if ((DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD).equals(documentReferenceTaskType)) {
            final String statisticsID = newDoc.getLastStatsId();
            final SourceDocumentProcessingStatistics sourceDocumentProcessingStatistics =
                    sourceDocumentProcessingStatisticsDao.read(statisticsID);
            try {
                headers = sourceDocumentProcessingStatistics.getHttpResponseHeaders();
            } catch (Exception e) {
                headers = new HashMap<>();
            }
        }

        return headers;
    }

    /**
     * Checks if any job was started but due to an issue of this node it has been abandoned.
     */
    public static void checkForAbandonedJobs(ProcessingJobDao processingJobDao, ClusterMasterConfig clusterMasterConfig,
                                       LoggingAdapter LOG ) {
        LOG.info("======== Looking for abandoned jobs from MongoDB ========");
        final Page page = new Page(0, clusterMasterConfig.getJobsPerIP());
        List<ProcessingJob> all;
        do {
            all = processingJobDao.getJobsWithState(JobState.RUNNING, page);

            for (final ProcessingJob job : all) {
                final ProcessingJob newProcessingJob = job.withState(JobState.READY);
                processingJobDao.update(newProcessingJob, clusterMasterConfig.getWriteConcern());
            }
        } while (all.size() != 0);

        do {
            all = processingJobDao.getJobsWithState(JobState.LOADED, page);

            for (final ProcessingJob job : all) {
                final ProcessingJob newProcessingJob = job.withState(JobState.READY);
                processingJobDao.update(newProcessingJob, clusterMasterConfig.getWriteConcern());
            }
        } while (all.size() != 0);

        LOG.info("======== Done with abandoned jobs ========");
    }

}