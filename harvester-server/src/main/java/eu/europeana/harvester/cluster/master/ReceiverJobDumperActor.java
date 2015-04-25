package eu.europeana.harvester.cluster.master;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.pattern.Patterns;
import akka.util.Timeout;
import eu.europeana.harvester.cluster.domain.ClusterMasterConfig;
import eu.europeana.harvester.cluster.domain.TaskState;
import eu.europeana.harvester.cluster.domain.messages.DoneProcessing;
import eu.europeana.harvester.cluster.domain.messages.RetrieveUrl;
import eu.europeana.harvester.cluster.domain.messages.inner.GetTask;
import eu.europeana.harvester.cluster.domain.messages.inner.GetTaskStatesPerJob;
import eu.europeana.harvester.cluster.domain.messages.inner.RemoveJob;
import eu.europeana.harvester.db.ProcessingJobDao;
import eu.europeana.harvester.domain.JobState;
import eu.europeana.harvester.domain.ProcessingJob;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ReceiverJobDumperActor extends UntypedActor {

    private final LoggingAdapter LOG = Logging.getLogger(getContext().system(), this);

    /**
     * Contains all the configuration needed by this actor.
     */
    private final ClusterMasterConfig clusterMasterConfig;

    /**
     * A wrapper class for all important data (ips, loaded jobs, jobs in progress etc.)
     */
    private ActorRef accountantActor;




    /**
     * ProcessingJob DAO object which lets us to read and store data to and from the database.
     */
    private final ProcessingJobDao processingJobDao;





    public ReceiverJobDumperActor(final ClusterMasterConfig clusterMasterConfig,
                                  final ActorRef accountantActor,
                                  final ProcessingJobDao processingJobDao){
        LOG.info("ReceiverJobDumperActor constructor");

        this.clusterMasterConfig = clusterMasterConfig;
        this.accountantActor = accountantActor;
        this.processingJobDao = processingJobDao;
    }

    @Override
    public void onReceive(Object message) throws Exception {

        if(message instanceof DoneProcessing) {
            final DoneProcessing doneProcessing = (DoneProcessing) message;
            markDone(doneProcessing);
            return;
        }
    }



    /**
     * Marks task as done and save it's statistics in the DB.
     * If one job has finished all his tasks then the job also will be marked as done(FINISHED).
     * @param msg - the message from the slave actor with url, jobId and other statistics
     */
    private void markDone(DoneProcessing msg) {

        final String jobId = msg.getJobId();

        List<TaskState> taskStates = new ArrayList<>();
        final Timeout timeout = new Timeout(Duration.create(10, TimeUnit.SECONDS));
        Future<Object> future = Patterns.ask(accountantActor, new GetTaskStatesPerJob(jobId), timeout);
        try {
            taskStates = (List<TaskState>) Await.result(future, timeout.duration());
        } catch (Exception e) {
            LOG.error("Error at markDone->GetTaskStatesPerJob: {}", e);
        }

        RetrieveUrl retrieveUrl = null;
        future = Patterns.ask(accountantActor, new GetTask(msg.getTaskID()), timeout);
        try {
            retrieveUrl = (RetrieveUrl) Await.result(future, timeout.duration());
        } catch (Exception e) {
            LOG.error("Error at markDone->GetTask: {}", e);
        }

        if(retrieveUrl != null && !retrieveUrl.getId().equals("")) {
            final String ipAddress = retrieveUrl.getIpAddress();
            checkJobStatus(jobId, taskStates, ipAddress);
        }
    }


    /**
     * Checks if a job is done, and if it's done than generates an event.
     * @param jobID the unique ID of the job
     * @param states all request states the job
     */
    private void checkJobStatus(final String jobID, final List<TaskState> states, final String ipAddress) {
        boolean allDone = true;
        for (final TaskState state : states) {
            if(!(TaskState.DONE).equals(state)) {
                allDone = false;
                break;
            }
        }

        if(allDone) {
            final ProcessingJob processingJob = processingJobDao.read(jobID);
            final ProcessingJob newProcessingJob = processingJob.withState(JobState.FINISHED);
            processingJobDao.update(newProcessingJob, clusterMasterConfig.getWriteConcern());
            accountantActor.tell(new RemoveJob(newProcessingJob.getId(),ipAddress), getSelf());


        }
    }
}