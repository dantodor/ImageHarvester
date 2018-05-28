package eu.europeana.harvester.cluster;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Address;
import akka.cluster.Cluster;
import akka.routing.FromConfig;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.Slf4jReporter;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.google.common.collect.Lists;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;
import eu.europeana.harvester.cluster.domain.NodeMasterConfig;
import eu.europeana.harvester.cluster.slave.NodeSupervisor;
import eu.europeana.harvester.cluster.slave.SlaveMetrics;
import eu.europeana.harvester.cluster.slave.validator.ImageMagicValidator;
import eu.europeana.harvester.db.MediaStorageClient;
import eu.europeana.harvester.db.dummy.DummyMediaStorageClientImpl;
import eu.europeana.harvester.db.s3.S3Configuration;
import eu.europeana.harvester.db.s3.S3MediaClientStorage;
import eu.europeana.harvester.db.swift.SwiftConfiguration;
import eu.europeana.harvester.db.swift.SwiftMediaStorageClientImpl;
import eu.europeana.harvester.httpclient.response.ResponseType;
import eu.europeana.harvester.util.CachingUrlResolver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public class Slave {

    public static final CachingUrlResolver URL_RESOLVER = new CachingUrlResolver();

    private static final Logger LOG = LogManager.getLogger(Slave.class.getName());

    private final String[] args;

    private ActorSystem system;

    private Config config;

    private static final String containerName = "swiftUnitTesting";


    public Slave(String[] args) {
        this.args = args;
    }

    public void init(Slave slave) throws Exception {
        //allRequirementsAreMetOrThrowException();

//        ConsoleReporter reporter = ConsoleReporter.forRegistry(metrics)
//                .convertRatesTo(TimeUnit.SECONDS)
//                .convertDurationsTo(TimeUnit.MILLISECONDS)
//                .build();


        String configFilePath;

        if (args.length == 0) {
            configFilePath = "./extra-files/config-files/slave.conf";
        } else {
            configFilePath = args[0];
        }

        final File configFile = new File(configFilePath);
        if (!configFile.exists()) {
            LOG.error("CLUSTER Slave Config file not found!");
            System.exit(-1);
        }

        config = ConfigFactory.parseFileAnySyntax(configFile, ConfigParseOptions.defaults()
                .setSyntax(ConfigSyntax.CONF));

//        final ExecutorService bossPool = Executors.newCachedThreadPool();
//        final ExecutorService workerPool = Executors.newCachedThreadPool();
//
//        final ChannelFactory channelFactory = new NioClientSocketChannelFactory(bossPool, workerPool);

        final ResponseType responseType;

        if ("diskStorage".equals(config.getString("slave.responseType"))) {
            responseType = ResponseType.DISK_STORAGE;
        } else {
            responseType = ResponseType.MEMORY_STORAGE;
        }

        final String pathToSave = config.getString("slave.pathToSave");
        final File dir = new File(pathToSave);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        final String source = config.getString("media-storage.source");
        final String colorMapPath = config.getString("slave.colorMap");

        final Integer nrOfDownloaderSlaves = config.getInt("slave.nrOfDownloaderSlaves");
        final Integer nrOfExtractorSlaves = config.getInt("slave.nrOfExtractorSlaves");
        final Integer nrOfPingerSlaves = config.getInt("slave.nrOfPingerSlaves");
        final Integer nrOfRetries = config.getInt("slave.nrOfRetries");
        final Integer taskNrLimit = config.getInt("slave.taskNrLimit");

        final NodeMasterConfig nodeMasterConfig = new NodeMasterConfig(nrOfDownloaderSlaves, nrOfExtractorSlaves,
                nrOfPingerSlaves, nrOfRetries, taskNrLimit, pathToSave, responseType, source, colorMapPath);

        final String mediaStorageClientType = config.hasPath("media-storage-type") ? config.getString("media-storage-type") : "DUMMY";

        MediaStorageClient mediaStorageClient = null;
        try {

            if ("SWIFT".equalsIgnoreCase(mediaStorageClientType)) {
                LOG.debug("CLUSTER SLAVE Using swift as media-storage");
                mediaStorageClient = new SwiftMediaStorageClientImpl(SwiftConfiguration.valueOf(config.getConfig("media-storage")));

            } else if("S3".equalsIgnoreCase(mediaStorageClientType)){
                mediaStorageClient = new S3MediaClientStorage(S3Configuration.valueOf(config.getConfig("media-storage")));
            } else {
                LOG.debug("CLUSTER SLAVE Using dummy as media-storage");
                mediaStorageClient = new DummyMediaStorageClientImpl();

            }
        } catch (Exception e) {
            LOG.error("CLUSTER SLAVE Error: connection failed to media-storage " + e.getMessage());
            e.printStackTrace();
            System.exit(-1);
        }

        Slf4jReporter reporter = Slf4jReporter.forRegistry(SlaveMetrics.METRIC_REGISTRY)
                .outputTo(org.slf4j.LoggerFactory.getLogger("metrics"))
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();

        reporter.start(60, TimeUnit.SECONDS);

        Graphite graphite = new Graphite(new InetSocketAddress(config.getString("metrics.graphiteServer"),
                config.getInt("metrics.graphitePort")));
        GraphiteReporter reporter2 = GraphiteReporter.forRegistry(SlaveMetrics.METRIC_REGISTRY)
                .prefixedWith(config.getString("metrics.slaveID"))
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .filter(MetricFilter.ALL)
                .build(graphite);
        reporter2.start(1, TimeUnit.MINUTES);


        system = ActorSystem.create("ClusterSystem", config);

        final ActorRef masterSender = system.actorOf(FromConfig.getInstance().props(), "masterSender");

        NodeSupervisor.createActor(system, slave, masterSender,nodeMasterConfig,
                mediaStorageClient, SlaveMetrics.METRIC_REGISTRY);

        Cluster cluster = Cluster.get(system);
        Address a = new Address("akka.tcp","ClusterSystem",
                "akka-seed-0.akka-seed.default.svc.cluster.local",2551);
        cluster.join(a);

        //system.actorOf(Props.create(MetricsListener.class), "metricsListener");
    }


    public void reinit(Slave slave) throws Exception {
        //allRequirementsAreMetOrThrowException();


        final ResponseType responseType;

        if ("diskStorage".equals(config.getString("slave.responseType"))) {
            responseType = ResponseType.DISK_STORAGE;
        } else {
            responseType = ResponseType.MEMORY_STORAGE;
        }

        final String pathToSave = config.getString("slave.pathToSave");
        final File dir = new File(pathToSave);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        final String source = config.getString("media-storage.source");
        final String colorMapPath = config.getString("slave.colorMap");

        final Integer nrOfDownloaderSlaves = config.getInt("slave.nrOfDownloaderSlaves");
        final Integer nrOfExtractorSlaves = config.getInt("slave.nrOfExtractorSlaves");
        final Integer nrOfPingerSlaves = config.getInt("slave.nrOfPingerSlaves");
        final Integer nrOfRetries = config.getInt("slave.nrOfRetries");
        final Integer taskNrLimit = config.getInt("slave.taskNrLimit");

        final NodeMasterConfig nodeMasterConfig = new NodeMasterConfig(nrOfDownloaderSlaves, nrOfExtractorSlaves,
                nrOfPingerSlaves, nrOfRetries, taskNrLimit, pathToSave, responseType, source, colorMapPath);

        final String mediaStorageClientType = config.hasPath("media-storage-type") ? config.getString("media-storage-type") : "DUMMY";

        MediaStorageClient mediaStorageClient = null;
        try {

            if ("SWIFT".equalsIgnoreCase(mediaStorageClientType)) {
                LOG.debug("CLUSTER SLAVE Using swift as media-storage");
                mediaStorageClient = new SwiftMediaStorageClientImpl(SwiftConfiguration.valueOf(config.getConfig("media-storage")));

            } else {
                LOG.debug("CLUSTER SLAVE Using dummy as media-storage");
                mediaStorageClient = new DummyMediaStorageClientImpl();

            }
        } catch (Exception e) {
            LOG.error("CLUSTER SLAVE Error: connection failed to media-storage " + e.getMessage());
            e.printStackTrace();
            System.exit(-1);
        }



        system = ActorSystem.create("ClusterSystem", config);

        final ActorRef masterSender = system.actorOf(FromConfig.getInstance().props(), "masterSender");

        NodeSupervisor.createActor(system, slave, masterSender,nodeMasterConfig,
                mediaStorageClient, SlaveMetrics.METRIC_REGISTRY);


    }



    public void restart() {
        LOG.debug("CLUSTER SLAVE Shutting down the actor system, restart.");
        SlaveMetrics.Worker.Slave.restartCounter.inc();
        system.shutdown();
        system.awaitTermination();
        //sleep 5 minutes
        try {
            Thread.sleep(300000l);
        } catch (InterruptedException e) {
            LOG.error(e.getMessage());
        }

        LOG.debug("trying to restart the actor system.");

        try {
            this.reinit(this);

        } catch ( Exception e ){
            LOG.error("Init threw exception",e);
        }

    }

    public ActorSystem getActorSystem() {
        return system;
    }

//    public static void allRequirementsAreMetOrThrowException() throws Exception {
//        new ImageMagicValidator(Lists.newArrayList("ImageMagick 6.9.0","ImageMagick 7.0")).doNothingOrThrowException();
//    }

    public static void main(String[] args) throws Exception {
        final Slave slave = new Slave(args);
        slave.init(slave);
        //slave.start();
    }

}
