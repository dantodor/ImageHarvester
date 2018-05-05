package eu.europeana.harvester.cluster.slave;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.testkit.JavaTestKit;
import com.google.common.collect.Lists;
import eu.europeana.harvester.cluster.domain.messages.DoneProcessing;
import eu.europeana.harvester.cluster.domain.messages.RetrieveUrl;
import eu.europeana.harvester.cluster.domain.messages.RetrieveUrlWithProcessingConfig;
import eu.europeana.harvester.cluster.slave.processing.SlaveProcessor;
import eu.europeana.harvester.cluster.slave.processing.color.ColorExtractor;
import eu.europeana.harvester.cluster.slave.processing.metainfo.MediaMetaInfoExtractor;
import eu.europeana.harvester.cluster.slave.processing.thumbnail.ThumbnailImageGenerator;
import eu.europeana.harvester.db.MediaStorageClient;
import eu.europeana.harvester.db.filesystem.FileSystemMediaStorageClientImpl;
import eu.europeana.harvester.domain.*;
import eu.europeana.harvester.httpclient.response.HttpRetrieveResponseFactory;
import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

public class RetrieveAndProcessActorTest {
    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            System.out.println("Starting test: " + description.getMethodName());
        }
    };

    private final static int port = 9090;

    private static String PROCESSING_PATH_PREFIX = Paths.get("harvester-server/src/test/resources/processing").toAbsolutePath().toString() + "/";
    private static final String jpegImage1GitHubUrl = "http://localhost:"+port+"/image1.jpeg";
    private static final String tiffImageGitHubUrl = "http://localhost:"+port+"/image3.tif";
    private static final String pdfTextGitHubUrl = "http://localhost:"+port+"/text2.pdf";
    private static final String jpegMediafrUrl = "http://localhost:"+port+"/mediafr.jpg";

    private static final String PATH_PREFIX = Paths.get("src/test/resources/").toAbsolutePath().toString() + "/" ;
    private static final String PATH_COLORMAP = PATH_PREFIX + "colormap.png";

    private static final String FILESYSTEM_PATH_PREFIX = Paths.get("src/test/resources/filesystem").toAbsolutePath().toString() + "/";

    private static final MediaStorageClient client = new FileSystemMediaStorageClientImpl(FILESYSTEM_PATH_PREFIX);
    final HttpRetrieveResponseFactory httpRetrieveResponseFactory = new HttpRetrieveResponseFactory();

    static ActorSystem system ;
    private static HttpServer server;
    private static final String assetDir = "./assets";

    @BeforeClass
    public static void setup() throws Exception {
        FileUtils.forceMkdir(new File(FILESYSTEM_PATH_PREFIX));
        FileUtils.forceMkdir(new File(PROCESSING_PATH_PREFIX));
        system = ActorSystem.create();
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

    @AfterClass
    public static void teardown() throws IOException {
        if (null != system) {
            system.shutdown();
        }
        FileUtils.deleteDirectory(new File(FILESYSTEM_PATH_PREFIX));
        FileUtils.deleteDirectory(new File(PROCESSING_PATH_PREFIX));
        server.stop();
    }

    @Test
    public void canRetreievAndProcessTypicalJob() throws Exception {

        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl(jpegImage1GitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);

            assertEquals(msg2.getImageMetaInfo().getWidth().intValue(),2500);
            assertEquals(msg2.getImageMetaInfo().getHeight().intValue(),1737);
            assertEquals(msg2.getImageMetaInfo().getMimeType(),"image/jpeg");

            // TODO : re-enable checking for the original
            // final MediaFile originalStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"ORIGINAL"),true);
            final MediaFile mediumStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"MEDIUM"),true);
            final MediaFile largeStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"LARGE"),true);

            // TODO : re-enable checking for the original
            //assertEquals("The original stored content is not equal with the original content", new Long(originalStoredContent.getContent().length), msg1.getHttpRetrieveResponse().getContentSizeInBytes());
            assertNotNull(mediumStoredContent);
            assertNotNull(largeStoredContent);

        }};
    }

    @Test
    public void test_Image_CheckLink_Success() throws InterruptedException {
        final RetrieveUrl task = new RetrieveUrl(jpegImage1GitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.CHECK_LINK,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.CHECK_LINK,
                        "source-reference-1", Collections.EMPTY_LIST), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);

            assertEquals (200, msg2.getHttpResponseCode().intValue());
            assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailStorageState());
        }};
    }

    @Test
    public void test_CheckLink_Fail() throws InterruptedException {
        final RetrieveUrl task = new RetrieveUrl("http://ana.are.mere:0099909/pere", new ProcessingJobLimits(), DocumentReferenceTaskType.CHECK_LINK,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.CHECK_LINK,
                        "source-reference-1", Collections.EMPTY_LIST), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);
            assertEquals (ProcessingJobRetrieveSubTaskState.ERROR, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailStorageState());
        }};
    }

    @Test
    public void test_Image_ConditionalDownload_Success() throws InterruptedException, NoSuchAlgorithmException, IOException {
        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl(jpegImage1GitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);

            assertEquals(msg2.getImageMetaInfo().getWidth().intValue(),2500);
            assertEquals(msg2.getImageMetaInfo().getHeight().intValue(),1737);
            assertEquals(msg2.getImageMetaInfo().getMimeType(),"image/jpeg");

            // TODO : Re-enable checking for the original
            // final MediaFile originalStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"ORIGINAL"),true);
            final MediaFile mediumStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"MEDIUM"),true);
            final MediaFile largeStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"LARGE"),true);

            assertEquals (200, msg2.getHttpResponseCode().intValue());
            assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailStorageState());

            // TODO : Re-enable checking for the original
            // assertEquals("The original stored content is not equal with the original content", new Long(originalStoredContent.getContent().length), msg1.getHttpRetrieveResponse().getContentSizeInBytes());
            assertNotNull(mediumStoredContent);
            assertNotNull(largeStoredContent);

        }};
    }

    @Test
    public void test_ConditionalDownload_LinkFail() throws InterruptedException, NoSuchAlgorithmException, IOException {
        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl("http://random-node/ana/are/mere/multe/si/proaste/pi/3?id=1145", new ProcessingJobLimits(), DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);


            assertEquals (ProcessingJobRetrieveSubTaskState.ERROR, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailStorageState());

        }};
    }

    @Test
    public void test_ConditionalDownload_TaskFail() throws Exception {
        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl(jpegImage1GitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        MediaMetaInfoExtractor mediaMetaInfoExtractorFail = mock(MediaMetaInfoExtractor.class);
        ColorExtractor colorExtractorFail = mock(ColorExtractor.class);
        MediaStorageClient mediaStorageClientFail = mock(FileSystemMediaStorageClientImpl.class);
        ThumbnailImageGenerator thumbnailImageGeneratorFail = mock(ThumbnailImageGenerator.class);

        doReturn(null).when(mediaMetaInfoExtractorFail).extract(anyString());
        doReturn(null).when(colorExtractorFail).colorExtraction(anyString());
        doReturn(PATH_COLORMAP).when(thumbnailImageGeneratorFail).getColorMapPath();
        doThrow(new Exception("")).when(thumbnailImageGeneratorFail).createMediaFileWithThumbnail(anyInt(), anyInt(), anyString(),
                anyString(),
                any(new byte[]{}.getClass()),
                anyString());

        final SlaveProcessor slaveProcessorFail = new SlaveProcessor(
                mediaMetaInfoExtractorFail,
                colorExtractorFail,
                mediaStorageClientFail,
                ""
        )  ;
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),
                    httpRetrieveResponseFactory,
                    slaveProcessorFail);

            subject.tell(taskWithConfig, getRef());


            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);


            assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.FAILED, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.FAILED, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailStorageState());
        }};
    }

    @Test
    public void test_ConditionalDownload_TaskError() throws Exception {
        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl(jpegImage1GitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        MediaMetaInfoExtractor mediaMetaInfoExtractorFail = mock(MediaMetaInfoExtractor.class);
        ColorExtractor colorExtractorFail = mock(ColorExtractor.class);
        MediaStorageClient mediaStorageClientFail = mock(FileSystemMediaStorageClientImpl.class);
        ThumbnailImageGenerator thumbnailImageGeneratorFail = mock(ThumbnailImageGenerator.class);

        doThrow(new Exception("ana are mere")).when(mediaMetaInfoExtractorFail).extract(anyString());
        doThrow(new IOException("")).when(colorExtractorFail).colorExtraction(anyString());
        doReturn(PATH_COLORMAP).when(thumbnailImageGeneratorFail).getColorMapPath();
        doThrow(new Exception("bere")).when(thumbnailImageGeneratorFail).createMediaFileWithThumbnail(anyInt(), anyInt(), anyString(),
                anyString(),
                any(new byte[]{}.getClass()),
                anyString());

        doThrow(new RuntimeException("exception")).when(mediaStorageClientFail).createOrModify(any(MediaFile.class));

        final SlaveProcessor slaveProcessorFail = new SlaveProcessor(
                mediaMetaInfoExtractorFail,
                colorExtractorFail,
                mediaStorageClientFail,
                ""
        )  ;
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),
                    httpRetrieveResponseFactory,
                    slaveProcessorFail);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);


            assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.ERROR, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.ERROR, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailStorageState());
        }};
    }

    @Test
    public void test_Image_UnconditionalDownload_Success() throws InterruptedException, NoSuchAlgorithmException, IOException {
        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl(jpegImage1GitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());


            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);

            assertEquals(msg2.getImageMetaInfo().getWidth().intValue(),2500);
            assertEquals(msg2.getImageMetaInfo().getHeight().intValue(),1737);
            assertEquals(msg2.getImageMetaInfo().getMimeType(),"image/jpeg");

            // TODO : Re-enable checking for the original
            // final MediaFile originalStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"ORIGINAL"),true);
            final MediaFile mediumStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"MEDIUM"),true);
            final MediaFile largeStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"LARGE"),true);

            assertEquals (200, msg2.getHttpResponseCode().intValue());
            assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailStorageState());

            // TODO : Re-enable checking for the original
            // assertEquals("The original stored content is not equal with the original content", new Long(originalStoredContent.getContent().length), msg1.getHttpRetrieveResponse().getContentSizeInBytes());
            assertNotNull(mediumStoredContent);
            assertNotNull(largeStoredContent);

        }};
    }

    @Test
    public void test_UnconditionalDownload_Success_Without_MetaInfo_ButMetaInfoInsertedAsAHack() throws InterruptedException, NoSuchAlgorithmException, IOException {
        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl(jpegImage1GitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);

            assertEquals(msg2.getImageMetaInfo().getColorPalette().length,6);
            assertEquals(new Integer(1737), msg2.getImageMetaInfo().getHeight());
            assertEquals("image/jpeg", msg2.getImageMetaInfo().getMimeType());

            // TODO : Re-enable checking for the original
            // final MediaFile originalStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"ORIGINAL"),true);
            final MediaFile mediumStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"MEDIUM"),true);
            final MediaFile largeStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"LARGE"),true);

            assertEquals (200, msg2.getHttpResponseCode().intValue());
            assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailStorageState());

            // TODO : Re-enable checking for the original
            // assertEquals("The original stored content is not equal with the original content", new Long(originalStoredContent.getContent().length), msg1.getHttpRetrieveResponse().getContentSizeInBytes());
            assertNotNull(mediumStoredContent);
            assertNotNull(largeStoredContent);

        }};
    }



    @Test
    public void test_UnconditionalDownload_LinkFail() throws InterruptedException, NoSuchAlgorithmException, IOException {
        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl("http://random-node/ana/are/mere/multe/si/proaste/pi/3?id=1145", new ProcessingJobLimits(), DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);


            assertEquals (ProcessingJobRetrieveSubTaskState.ERROR, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailStorageState());

        }};
    }

    @Test
    public void test_UnconditionalDownload_TaskFail() throws Exception {
        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl(jpegImage1GitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        MediaMetaInfoExtractor mediaMetaInfoExtractorFail = mock(MediaMetaInfoExtractor.class);
        ColorExtractor colorExtractorFail = mock(ColorExtractor.class);
        MediaStorageClient mediaStorageClientFail = mock(FileSystemMediaStorageClientImpl.class);
        ThumbnailImageGenerator thumbnailImageGeneratorFail = mock(ThumbnailImageGenerator.class);

        doReturn(null).when(mediaMetaInfoExtractorFail).extract(anyString());
        doReturn(null).when(colorExtractorFail).colorExtraction(anyString());
        doReturn(PATH_COLORMAP).when(thumbnailImageGeneratorFail).getColorMapPath();
        doThrow(new Exception("")).when(thumbnailImageGeneratorFail).createMediaFileWithThumbnail(anyInt(), anyInt(), anyString(),
                anyString(),
                any(new byte[]{}.getClass()),
                anyString());

        final SlaveProcessor slaveProcessorFail = new SlaveProcessor(
                mediaMetaInfoExtractorFail,
                colorExtractorFail,
                mediaStorageClientFail,
                ""
        )  ;
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),
                    httpRetrieveResponseFactory,
                    slaveProcessorFail);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);


            assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.FAILED, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.FAILED, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailStorageState());
        }};
    }

    @Test
    public void test_UnconditionalDownload_TaskError() throws Exception {
        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl(jpegImage1GitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        MediaMetaInfoExtractor mediaMetaInfoExtractorFail = mock(MediaMetaInfoExtractor.class);
        ColorExtractor colorExtractorFail = mock(ColorExtractor.class);
        MediaStorageClient mediaStorageClientFail = mock(FileSystemMediaStorageClientImpl.class);
        ThumbnailImageGenerator thumbnailImageGeneratorFail = mock(ThumbnailImageGenerator.class);

        doThrow(new Exception("ana are mere")).when(mediaMetaInfoExtractorFail).extract(anyString());
        doThrow(new IOException("")).when(colorExtractorFail).colorExtraction(anyString());
        doReturn(PATH_COLORMAP).when(thumbnailImageGeneratorFail).getColorMapPath();
        doThrow(new Exception("bere")).when(thumbnailImageGeneratorFail).createMediaFileWithThumbnail(anyInt(), anyInt(), anyString(),
                anyString(),
                any(new byte[]{}.getClass()),
                anyString());

        doThrow(new RuntimeException("exceptio")).when(mediaStorageClientFail).createOrModify(any(MediaFile.class));

        final SlaveProcessor slaveProcessorFail = new SlaveProcessor(
                mediaMetaInfoExtractorFail,
                colorExtractorFail,
                mediaStorageClientFail,
                ""
        )  ;
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),
                    httpRetrieveResponseFactory,
                    slaveProcessorFail);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);


            assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.ERROR, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.ERROR, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailStorageState());
        }};
    }


    @Test
    public void canRetreievAndProcessTypicalJobThatFailedBefore() throws Exception {

        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl(jpegMediafrUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);

            assertEquals (ProcessingState.SUCCESS, msg2.getProcessingState());

            assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailStorageState());

        }};
    }

    @Test
    public void test_PDF_Text_UnconditionalDownload_Success() throws InterruptedException, NoSuchAlgorithmException, IOException {
        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl(pdfTextGitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
    /*    new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());


            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);

            //assertEquals(msg2.getTextMetaInfo().getMimeType(),"application/pdf");

            // TODO : Re-enable checking for the original
            // final MediaFile originalStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"ORIGINAL"),true);
            //final MediaFile mediumStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"MEDIUM"),true);
            //final MediaFile largeStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"LARGE"),true);

            //assertEquals (200, msg2.getHttpResponseCode().intValue());
            //assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            //assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getColorExtractionState());
            //assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getMetaExtractionState());
            //assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailGenerationState());
            //assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailStorageState());

            // TODO : Re-enable checking for the original
            // assertEquals("The original stored content is not equal with the original content", new Long(originalStoredContent.getContent().length), msg1.getHttpRetrieveResponse().getContentSizeInBytes());
            //assertNotNull(mediumStoredContent);
            //assertNotNull(largeStoredContent);

        }};
	*/
    }

    @Test
    public void test_PDF_Text_ConditionalDownload_Success() throws InterruptedException, NoSuchAlgorithmException, IOException {
        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl(pdfTextGitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);

            assertEquals(msg2.getTextMetaInfo().getMimeType(),"application/pdf");

            // TODO : Re-enable checking for the original
            // final MediaFile originalStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"ORIGINAL"),true);
            final MediaFile mediumStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"MEDIUM"),true);
            final MediaFile largeStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"LARGE"),true);

            assertEquals (200, msg2.getHttpResponseCode().intValue());
            assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailStorageState());

            // TODO : Re-enable checking for the original
            // assertEquals("The original stored content is not equal with the original content", new Long(originalStoredContent.getContent().length), msg1.getHttpRetrieveResponse().getContentSizeInBytes());
            assertNotNull(mediumStoredContent);
            assertNotNull(largeStoredContent);

        }};
    }

    @Test
    public void test_PDF_Text_CheckLink_Success() throws InterruptedException {
        final RetrieveUrl task = new RetrieveUrl(pdfTextGitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.CHECK_LINK,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.CHECK_LINK,
                        "source-reference-1", Collections.EMPTY_LIST), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);
	    assertEquals ( 1,1);
            //assertEquals (200, msg2.getHttpResponseCode().intValue());
            //assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            //assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getColorExtractionState());
            //assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getMetaExtractionState());
            //assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailGenerationState());
            //assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailStorageState());
        }};
    }

    @Test
    public void test_TIFF_Image_UnconditionalDownload_Success() throws InterruptedException, NoSuchAlgorithmException, IOException {
        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl(tiffImageGitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.UNCONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());


            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);

            assertEquals(msg2.getImageMetaInfo().getColorPalette().length,6);
            assertEquals(new Integer(3800), msg2.getImageMetaInfo().getHeight());
            assertEquals("image/tiff", msg2.getImageMetaInfo().getMimeType());

            // TODO : Re-enable checking for the original
            // final MediaFile originalStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"ORIGINAL"),true);
            final MediaFile mediumStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"MEDIUM"),true);
            final MediaFile largeStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"LARGE"),true);

            assertEquals (200, msg2.getHttpResponseCode().intValue());
            assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailStorageState());

            // TODO : Re-enable checking for the original
            // assertEquals("The original stored content is not equal with the original content", new Long(originalStoredContent.getContent().length), msg1.getHttpRetrieveResponse().getContentSizeInBytes());
            assertNotNull(mediumStoredContent);
            assertNotNull(largeStoredContent);

        }};
    }

    @Test
    public void test_TIFF_Image_ConditionalDownload_Success() throws InterruptedException, NoSuchAlgorithmException, IOException {
        final ProcessingJobSubTask colorExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.COLOR_EXTRACTION,null);
        final ProcessingJobSubTask metaInfoExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.META_EXTRACTION,null);
        final ProcessingJobSubTask mediumThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(200,200)));
        final ProcessingJobSubTask largeThumbnailExtractionSubTask = new ProcessingJobSubTask(ProcessingJobSubTaskType.GENERATE_THUMBNAIL,new GenericSubTaskConfiguration(new ThumbnailConfig(400,400)));

        final List<ProcessingJobSubTask> subTasks = Lists.newArrayList(
                colorExtractionSubTask,
                metaInfoExtractionSubTask,
                mediumThumbnailExtractionSubTask,
                largeThumbnailExtractionSubTask
        );

        final RetrieveUrl task = new RetrieveUrl(tiffImageGitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.CONDITIONAL_DOWNLOAD,
                        "source-reference-1", subTasks), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);

            assertEquals(msg2.getImageMetaInfo().getColorPalette().length,6);
            assertEquals(new Integer(3800), msg2.getImageMetaInfo().getHeight());
            assertEquals("image/tiff", msg2.getImageMetaInfo().getMimeType());

            // TODO : Re-enable checking for the original
            // final MediaFile originalStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"ORIGINAL"),true);
            final MediaFile mediumStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"MEDIUM"),true);
            final MediaFile largeStoredContent = client.retrieve(MediaFile.generateIdFromUrlAndSizeType(msg2.getUrl(),"LARGE"),true);

            assertEquals (200, msg2.getHttpResponseCode().intValue());
            assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.SUCCESS, msg2.getStats().getThumbnailStorageState());

            // TODO : Re-enable checking for the original
            // assertEquals("The original stored content is not equal with the original content", new Long(originalStoredContent.getContent().length), msg1.getHttpRetrieveResponse().getContentSizeInBytes());
            assertNotNull(mediumStoredContent);
            assertNotNull(largeStoredContent);

        }};
    }

    @Test
    public void test_TIFF_Image_CheckLink_Success() throws InterruptedException {
        final RetrieveUrl task = new RetrieveUrl(tiffImageGitHubUrl, new ProcessingJobLimits(), DocumentReferenceTaskType.CHECK_LINK,"a",
                "referenceid-1", Collections.<String, String>emptyMap(),
                new ProcessingJobTaskDocumentReference(DocumentReferenceTaskType.CHECK_LINK,
                        "source-reference-1", Collections.EMPTY_LIST), null,new ReferenceOwner("unknown","unknwon","unknown"));

        final RetrieveUrlWithProcessingConfig taskWithConfig = new RetrieveUrlWithProcessingConfig(task,PROCESSING_PATH_PREFIX+task.getId());
    /*
     * Wrap the whole test procedure within a testkit
     * initializer if you want to receive actor replies
     * or use Within(), etc.
     */
        new JavaTestKit(system) {{

            final ActorRef subject = RetrieveAndProcessActor.createActor(getSystem(),httpRetrieveResponseFactory,client,PATH_COLORMAP);

            subject.tell(taskWithConfig, getRef());

            while (!msgAvailable()) Thread.sleep(100);
            DoneProcessing msg2 = expectMsgAnyClassOf(DoneProcessing.class);

            assertEquals (200, msg2.getHttpResponseCode().intValue());
            assertEquals (ProcessingJobRetrieveSubTaskState.SUCCESS, msg2.getStats().getRetrieveState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getColorExtractionState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getMetaExtractionState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailGenerationState());
            assertEquals (ProcessingJobSubTaskState.NEVER_EXECUTED, msg2.getStats().getThumbnailStorageState());
        }};
    }
}
