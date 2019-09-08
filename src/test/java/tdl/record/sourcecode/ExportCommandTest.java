package tdl.record.sourcecode;

import org.apache.commons.io.FileUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import support.TestGeneratedSrcsFile;
import tdl.record.sourcecode.snapshot.SnapshotTypeHint;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static support.TestUtils.writeFile;
import static support.recording.TestRecordingFrame.asFrame;

public class ExportCommandTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Rule
    public TestGeneratedSrcsFile recorder = new TestGeneratedSrcsFile(Arrays.asList(
            asFrame(Collections.singletonList("tag1"), (Path dst) -> {
                writeFile(dst, "test1.txt", "TEST1");
                return SnapshotTypeHint.KEY;
            }),
            asFrame(Collections.singletonList("x"), (Path dst) -> {
                writeFile(dst, "test1.txt", "TEST1TEST2");
                return SnapshotTypeHint.PATCH;
            }),
            asFrame(Collections.singletonList("tag12"), (Path dst) -> {
                writeFile(dst, "test2.txt", "TEST1TEST2");
                return SnapshotTypeHint.PATCH;
            }),
            asFrame(Collections.singletonList("tag3"), (Path dst) -> {
                writeFile(dst, "test2.txt", "TEST1TEST2");
                writeFile(dst, "subdir/test3.txt", "TEST3");
                return SnapshotTypeHint.KEY;
            }),
            asFrame(Collections.singletonList("tag3"), (Path dst) -> {
                // Empty folder
                return SnapshotTypeHint.PATCH;
            }),
            asFrame((Path dst) -> {
                writeFile(dst, "test1.txt", "TEST1TEST2");
                return SnapshotTypeHint.PATCH;
            }),
            asFrame((Path dst) -> {
                writeFile(dst, "test1.txt", "TEST1TEST2");
                return SnapshotTypeHint.KEY;
            }),
            asFrame((Path dst) -> {
                writeFile(dst, "test1.txt", "TEST1TEST2");
                return SnapshotTypeHint.PATCH;
            }),
            asFrame((Path dst) -> {
                writeFile(dst, "test1.txt", "TEST1TEST2");
                return SnapshotTypeHint.PATCH;
            }),
            asFrame((Path dst) -> {
                writeFile(dst, "test1.txt", "TEST1TEST2");
                return SnapshotTypeHint.KEY;
            })
    ));


    @Test
    public void run() throws IOException {
        Path inputFilePath = recorder.getFilePath();

        {
            Path destDir = exportTimestamp(inputFilePath, 0);
            assertEquals("TEST1", readFile(destDir, "test1.txt"));
        }

        {
            Path destDir = exportTimestamp(inputFilePath, 1);
            assertEquals("TEST1TEST2", readFile(destDir, "test1.txt"));
        }

        {
            Path destDir = exportTimestamp(inputFilePath, 2);
            assertEquals("TEST1TEST2", readFile(destDir, "test2.txt"));
        }

        {
            Path destDir = exportTimestamp(inputFilePath, 3);
            assertEquals("TEST3", readFile(destDir, "subdir/test3.txt"));
            assertEquals("TEST1TEST2", readFile(destDir, "test2.txt"));
        }

        {
            Path destDir = exportTag(inputFilePath, "tag12");
            assertEquals("TEST1TEST2", readFile(destDir, "test2.txt"));
        }

        {
            Path destDir = exportTag(inputFilePath, "tag3");
            assertEquals("TEST3", readFile(destDir, "subdir/test3.txt"));
        }
    }

    private Path exportTimestamp(Path inputFilePath, int timestamp) throws IOException {
        Path dir = folder.newFolder().toPath();
        ExportCommand exportCommand = new ExportCommand(inputFilePath.toString(), dir.toString(), timestamp);
        exportCommand.run();
        return dir;
    }

    private Path exportTag(Path inputFilePath, String tag) throws IOException {
        Path dir = folder.newFolder().toPath();
        ExportCommand exportCommand = new ExportCommand(inputFilePath.toString(), dir.toString(), tag);
        exportCommand.run();
        return dir;
    }

    private String readFile(Path parent, String path) throws IOException {
        Path resolvedPath = parent.resolve(path);
        if (!Files.exists(resolvedPath)) {
            throw new AssertionError("File does not exist: " + resolvedPath);
        }

        File file = resolvedPath.toFile();
        return FileUtils.readFileToString(file, Charset.defaultCharset());
    }
}
