package acceptance;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import support.TestSourceStreamRecorder;
import support.content.MultiStepSourceCodeProvider;
import support.time.FakeTimeSource;
import tdl.record.sourcecode.content.SourceCodeProvider;
import tdl.record.sourcecode.metrics.SourceCodeRecordingMetricsCollector;
import tdl.record.sourcecode.record.SourceCodeRecorder;
import tdl.record.sourcecode.record.SourceCodeRecorderException;
import tdl.record.sourcecode.snapshot.SnapshotType;
import tdl.record.sourcecode.snapshot.SnapshotTypeHint;
import tdl.record.sourcecode.snapshot.file.Reader;
import tdl.record.sourcecode.snapshot.file.Segment;
import tdl.record.sourcecode.snapshot.file.ToGitConverter;
import tdl.record.sourcecode.snapshot.helpers.DirectoryDiffUtils;
import tdl.record.sourcecode.snapshot.helpers.DirectoryPatch;
import tdl.record.sourcecode.snapshot.helpers.GitHelper;
import tdl.record.sourcecode.time.SystemMonotonicTimeSource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static tdl.record.sourcecode.snapshot.SnapshotType.KEY;
import static tdl.record.sourcecode.snapshot.SnapshotType.PATCH;

public class CanRecordSourceCodeAccTest {

    private static final int TIME_TO_TAKE_A_SNAPSHOT = 1000;
    private static final Duration INDEFINITE = Duration.of(999, ChronoUnit.HOURS);


    @TempDir
    Path testFolder;

    @Test
    public void should_be_able_to_record_history_at_a_given_rate() throws Exception {
        Path outputFilePath = testFolder.resolve("output.srcs");

        List<SourceCodeProvider> sourceCodeHistory = Arrays.asList(
                (Path dst) -> {
                    writeTextFile(dst, "test1.txt", "TEST1");
                    return SnapshotTypeHint.ANY;
                },
                (Path dst) -> {
                    writeTextFile(dst, "test1.txt", "TEST1TEST2");
                    return SnapshotTypeHint.ANY;
                },
                (Path dst) -> {
                    writeTextFile(dst, "test2.txt", "TEST1TEST2");
                    return SnapshotTypeHint.ANY;
                },
                (Path dst) -> {
                    writeTextFile(dst, "test2.txt", "TEST1TEST2");
                    writeTextFile(dst, "subdir/test3.txt", "TEST3");
                    return SnapshotTypeHint.ANY;
                },
                (Path dst) -> {
                    writeTextFile(dst, "test2.txt", "TEST1TEST2");
                    writeTextFile(dst, "test.empty.txt", "");
                    return SnapshotTypeHint.ANY;
                },
                (Path dst) -> {
                    /* Empty folder */
                    return SnapshotTypeHint.ANY;
                });

        SourceCodeRecordingMetricsCollector sourceCodeRecordingListener = new SourceCodeRecordingMetricsCollector();
        SourceCodeRecorder sourceCodeRecorder = new SourceCodeRecorder.Builder(
                new MultiStepSourceCodeProvider(sourceCodeHistory), outputFilePath)
                .withTimeSource(new FakeTimeSource())
                .withSnapshotEvery(1, TimeUnit.SECONDS)
                .withKeySnapshotSpacing(3)
                .withRecordingListener(sourceCodeRecordingListener)
                .build();
        sourceCodeRecorder.start(Duration.of(sourceCodeHistory.size(), ChronoUnit.SECONDS));
        sourceCodeRecorder.close();

        // Test the structure of the file
        try (Reader reader = new Reader(outputFilePath.toFile())) {
            List<Segment> segments = reader.getSegments();
            List<SnapshotType> snapshotTypes = segments.stream().map(Segment::getType).collect(Collectors.toList());
            assertSnapshotTypesAre(Arrays.asList(KEY, PATCH, PATCH, KEY, PATCH, PATCH), snapshotTypes);
            assertTimestampsAreConsistentWith(1, TimeUnit.SECONDS, segments);
        }

        // Test the contents of the file
        File gitExportFolder = Files.createTempDirectory(testFolder, "dir").toFile();
        ToGitConverter converter = new ToGitConverter(outputFilePath, gitExportFolder.toPath());
        converter.convert();
        assertContentMatches(sourceCodeHistory, gitExportFolder);

        // Test the collected recording metrics
        assertThat(sourceCodeRecordingListener.getTotalSnapshots(), is(sourceCodeHistory.size()));
        assertThat(sourceCodeRecordingListener.getLastSnapshotProcessingTimeNano(), is(2L));
    }

    @Test
    public void should_be_able_to_tag_a_particular_moment() throws Exception {
        Path outputFilePath = testFolder.resolve("tagged_snapshots.srcs");

        List<SourceCodeProvider> sourceCodeHistory = Arrays.asList(
                (Path dst) -> {
                    writeTextFile(dst, "test1.txt", "TEST1");
                    return SnapshotTypeHint.ANY;
                },
                (Path dst) -> {
                    writeTextFile(dst, "test1.txt", "TEST2");
                    return SnapshotTypeHint.ANY;
                }
        );

        // Run a recording on a separate thread
        SourceCodeRecorder sourceCodeRecorder = indefiniteRecorder(outputFilePath, sourceCodeHistory);
        Thread recordingThread = createRecordingThread(sourceCodeRecorder);
        recordingThread.start();

        // Trigger the tagged snapshot
        Thread.sleep(TIME_TO_TAKE_A_SNAPSHOT);
        sourceCodeRecorder.tagCurrentState("testTag");
        Thread.sleep(TIME_TO_TAKE_A_SNAPSHOT);
        sourceCodeRecorder.stop();

        // Wait for recording to finish
        recordingThread.join();

        try (Reader reader = new Reader(outputFilePath.toFile())) {
            List<Segment> snapshots = reader.getSegments();
            assertThat(snapshots.size(), is(3)); // 1 initial + 1 tag + 1 final
            assertEquals(snapshots.get(1).getTag().trim(), "testTag");
        }
        File gitDir = Files.createTempDirectory(testFolder, "dir").toFile();
        ToGitConverter converter = new ToGitConverter(outputFilePath, gitDir.toPath());
        converter.convert();

        //See the git tags
        Git git = Git.open(gitDir);

        assertThat(GitHelper.getTags(git), equalTo(singletonList("testTag")));
    }

    @Test
    public void on_stop_should_process_all_the_tags_before_stopping() throws Exception {
        Path outputFilePath = testFolder.resolve("burst_tags.srcs");

        List<SourceCodeProvider> sourceCodeHistory = Arrays.asList(
                dst -> {
                    writeTextFile(dst, "test1.txt", "TEST1");
                    return SnapshotTypeHint.ANY;
                },
                dst -> {
                    writeTextFile(dst, "test1.txt", "TEST2");
                    return SnapshotTypeHint.ANY;
                }
        );

        // Run a recording on a separate thread
        SourceCodeRecorder sourceCodeRecorder = indefiniteRecorder(outputFilePath, sourceCodeHistory);
        Thread recordingThread = createRecordingThread(sourceCodeRecorder);
        recordingThread.start();

        // Trigger the tagged snapshot
        Thread.sleep(TIME_TO_TAKE_A_SNAPSHOT);
        int numTags = 3;
        for (int i = 0; i < numTags; i++) {
            sourceCodeRecorder.tagCurrentState("testTag" + i);
        }
        sourceCodeRecorder.stop();

        // Wait for recording to finish
        recordingThread.join();

        // Ensure all tags have been captured
        List<String> tags = extractTags(outputFilePath);
        assertThat(tags.size(), is(4)); // 3 tags + final snapshot
    }

    @Test
    public void should_minimize_the_size_of_the_stream() throws Exception {
        Path onlyKeySnapshotsPath = testFolder.resolve("only_key_snapshots.bin");
        Path patchesAndKeySnapshotsPath = testFolder.resolve("patches_and_snapshots.bin");

        Path staticFolder = Paths.get("./src/test/resources/large_folder");
        int numberOfSnapshots = 10;
        TestSourceStreamRecorder.recordFolder(staticFolder, onlyKeySnapshotsPath,
                numberOfSnapshots, 1);
        TestSourceStreamRecorder.recordFolder(staticFolder, patchesAndKeySnapshotsPath,
                numberOfSnapshots, 5);

        long onlyKeySizeKB = onlyKeySnapshotsPath.toFile().length() / 1000;
        System.out.println("onlyKeySnapshots = " + onlyKeySizeKB + " KB");
        long patchesAndKeysSizeKB = patchesAndKeySnapshotsPath.toFile().length() / 1000;
        System.out.println("patchesAndKeySnapshots = " + patchesAndKeysSizeKB + " KB");
        assertThat("Size reduction", (int) (onlyKeySizeKB / patchesAndKeysSizeKB), equalTo(4));
    }


    //~~~~~~~~~~~~~ Helpers ~~~~~~~~~~

    private Thread createRecordingThread(SourceCodeRecorder sourceCodeRecorder) {
        return new Thread(() -> {
            try {
                sourceCodeRecorder.start(INDEFINITE);
            } catch (SourceCodeRecorderException e) {
                e.printStackTrace();
            }
            sourceCodeRecorder.close();
        });
    }

    private SourceCodeRecorder indefiniteRecorder(Path outputFilePath,
                                                  List<SourceCodeProvider> sourceCodeHistory) {
        return new SourceCodeRecorder.Builder(
                new MultiStepSourceCodeProvider(sourceCodeHistory), outputFilePath)
                .withTimeSource(new SystemMonotonicTimeSource())
                .withSnapshotEvery(999, TimeUnit.HOURS)
                .withKeySnapshotSpacing(1)
                .build();
    }

    private List<String> extractTags(Path outputFilePath) throws IOException {
        List<String> tags;
        try (Reader reader = new Reader(outputFilePath.toFile())) {
            List<Segment> snapshots = reader.getSegments();
            tags = snapshots.stream().map(Segment::getTag).map(String::trim).collect(Collectors.toList());
        }
        return tags;
    }

    private void writeTextFile(Path destinationFolder, String childFile, String content) throws IOException {
        File newFile1 = destinationFolder.resolve(childFile).toFile();
        FileUtils.writeStringToFile(newFile1, content, StandardCharsets.US_ASCII);
    }

    private void assertSnapshotTypesAre(List<SnapshotType> expectedSnapshotTypes, List<SnapshotType> actualSnapshots) {
        assertThat(actualSnapshots, equalTo(expectedSnapshotTypes));
    }

    private void assertContentMatches(List<SourceCodeProvider> sourceCodeHistory, File gitExportFolder) throws Exception {
        Git git = Git.open(gitExportFolder);
        Iterator<RevCommit> commitsIterator = git.log().call().iterator();
        List<String> commitIdsInChronologicalOrder = new ArrayList<>();
        while (commitsIterator.hasNext()) {
            commitIdsInChronologicalOrder.add(0, commitsIterator.next().getName());
        }

        assertThat("Number of captured snapshots", commitIdsInChronologicalOrder.size(), equalTo(sourceCodeHistory.size()));
        Path expected;
        for (int i = 0; i < commitIdsInChronologicalOrder.size(); i++) {
            expected = Files.createTempDirectory(testFolder, "dir");
            sourceCodeHistory.get(i).retrieveAndSaveTo(expected);
            git.checkout().setName(commitIdsInChronologicalOrder.get(i)).call();

            assertThat("Data of snapshot " + i, gitExportFolder.toPath(), hasSameData(expected));
        }
    }

    private Matcher<Path> hasSameData(Path expected) {
        return new TypeSafeMatcher<Path>() {

            @Override
            protected boolean matchesSafely(Path actual) {
                try {
                    DirectoryPatch patch = DirectoryDiffUtils.diffDirectories(expected, actual);
                    Map filtered = patch.getPatches().entrySet()
                            .stream()
                            .filter(map -> !map.getKey().startsWith(".git"))
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                    return filtered.isEmpty();
                } catch (Exception e) {
                    e.printStackTrace();
                    return false;
                }
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Matches the contents of folder");
            }

            @Override
            protected void describeMismatchSafely(Path actual, Description mismatchDescription) {
                mismatchDescription.appendText("differences detected:\n");
                mismatchDescription.appendText("expected: ").appendText(expected.toString()).appendText("\n");
                mismatchDescription.appendText("actual:   ").appendText(actual.toString());
            }
        };
    }

    @SuppressWarnings("SameParameterValue")
    private void assertTimestampsAreConsistentWith(int time, TimeUnit unit, List<Segment> snapshots) {
        for (int i = 0; i < snapshots.size(); i++) {
            assertThat("Timestamp of snapshot " + i,
                    (double) snapshots.get(i).getTimestampSec(), closeTo(unit.toSeconds(time * i), 0.01));
        }
    }
}
