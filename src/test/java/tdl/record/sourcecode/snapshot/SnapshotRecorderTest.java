package tdl.record.sourcecode.snapshot;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tdl.record.sourcecode.test.FileTestHelper;

public class SnapshotRecorderTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void takeSnapshot() throws IOException {
        Path directory = Paths.get("./src/test/resources/diff/test1/dir1/");
        Path tmpDir = folder.getRoot().toPath();
        FileUtils.copyDirectory(directory.toFile(), tmpDir.toFile());
        SnapshotRecorder recorder = new SnapshotRecorder(tmpDir);

        Snapshot snapshot1 = recorder.takeSnapshot();
        assertTrue(snapshot1 instanceof KeySnapshot);
        printSnapshot(snapshot1);

        appendString(tmpDir, "file1.txt", "\ndata1");
        Snapshot snapshot2 = recorder.takeSnapshot();
        assertTrue(snapshot2 instanceof PatchSnapshot);
        printSnapshot(snapshot2);

        appendString(tmpDir, "file2.txt", "\nLOREM");
        Snapshot snapshot3 = recorder.takeSnapshot();
        assertTrue(snapshot3 instanceof PatchSnapshot);
        printSnapshot(snapshot3);

        appendString(tmpDir, "subdir1/file1.txt", "\nIPSUM");
        Snapshot snapshot4 = recorder.takeSnapshot();
        assertTrue(snapshot4 instanceof PatchSnapshot);
        printSnapshot(snapshot4);

        appendString(tmpDir, "subdir1/file1.txt", "SIT");
        Snapshot snapshot5 = recorder.takeSnapshot();
        assertTrue(snapshot5 instanceof PatchSnapshot);
        printSnapshot(snapshot5);

        appendString(tmpDir, "subdir1/file1.txt", "AMENT");
        Snapshot snapshot6 = recorder.takeSnapshot();
        assertTrue(snapshot6 instanceof KeySnapshot);
        printSnapshot(snapshot6);
    }

    private static void printSnapshot(Snapshot snapshot) {
        //System.out.println(new String(snapshot.getData()));
        //do nothing
    }

    private static void appendString(Path dir, String path, String data) throws IOException {
        FileUtils.writeStringToFile(dir.resolve(path).toFile(), data, Charset.defaultCharset(), true);
    }

    @Test
    public void constructShouldCreateGitDirectory() {
        Path tmpDir = folder.getRoot().toPath();
        SnapshotRecorder recorder = new SnapshotRecorder(tmpDir);
        Path gitDir = recorder.getGitDirectory();
        assertTrue(gitDir.toFile().exists());
        assertTrue(gitDir.resolve(".git").toFile().exists());
    }

    @Test
    public void syncToGitDirectoryShouldCopyDirectory() throws IOException {
        Path tmpDir = folder.getRoot().toPath();
        try (SnapshotRecorder recorder = new SnapshotRecorder(tmpDir)) {
            Path gitDir = recorder.getGitDirectory();

            assertFalse(gitDir.resolve("file1.txt").toFile().exists());
            assertFalse(gitDir.resolve("file2.txt").toFile().exists());
            FileTestHelper.appendStringToFile(tmpDir, "file1.txt", "Hello World!");
            FileTestHelper.appendStringToFile(tmpDir, "file2.txt", "Lorem Ipsum!");

            recorder.syncToGitDirectory();
            assertTrue(gitDir.resolve("file1.txt").toFile().exists());
            assertTrue(gitDir.resolve("file2.txt").toFile().exists());

            assertFalse(gitDir.resolve("subdir/file1.txt").toFile().exists());
            FileTestHelper.appendStringToFile(tmpDir, "subdir/file1.txt", "Hello World!");

            recorder.syncToGitDirectory();
            assertTrue(gitDir.resolve("subdir").toFile().isDirectory());
            assertTrue(gitDir.resolve("subdir/file1.txt").toFile().exists());
        }
    }

    @Test
    public void syncToGitDirectoryShouldCopyDirectoryWithDotGitignore() throws IOException {
        Path tmpDir = folder.getRoot().toPath();
        try (SnapshotRecorder recorder = new SnapshotRecorder(tmpDir)) {
            Path gitDir = recorder.getGitDirectory();

            assertFalse(gitDir.resolve(".gitignore").toFile().exists());
            FileTestHelper.appendStringToFile(tmpDir, ".gitignore", "*.orig");

            recorder.syncToGitDirectory();
            assertTrue(gitDir.resolve(".gitignore").toFile().exists());
        }
    }

    @Test
    public void syncToGitDirectoryShouldCopyDirectoryWithoutDotGitDirectory() throws IOException, GitAPIException {
        Path tmpDir = folder.getRoot().toPath();
        try (SnapshotRecorder recorder = new SnapshotRecorder(tmpDir)) {
            Path gitDir = recorder.getGitDirectory();
            
            assertFalse(tmpDir.resolve(".git").toFile().exists());
            Git.init().setDirectory(tmpDir.toFile()).call();
            FileTestHelper.appendStringToFile(tmpDir, ".git/randomfile", "TEST");
            assertTrue(tmpDir.resolve(".git").toFile().exists());

            recorder.syncToGitDirectory();
            assertTrue(gitDir.resolve(".git").toFile().exists());
            assertFalse(gitDir.resolve(".git/randomfile").toFile().exists());
        }
    }
}
