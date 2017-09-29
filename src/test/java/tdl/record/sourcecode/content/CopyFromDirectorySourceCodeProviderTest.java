package tdl.record.sourcecode.content;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tdl.record.sourcecode.test.FileTestHelper;

public class CopyFromDirectorySourceCodeProviderTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Git git;
    private CopyFromDirectorySourceCodeProvider provider;
    private Path sourceFolderPath;
    private Path destination;
    private SourceFolder sourceFolder;


    @Before
    public void setUp() throws Exception {
        File sourceFolderFile = folder.newFolder();
        this.sourceFolderPath = sourceFolderFile.toPath();
        destination = folder.newFolder().toPath();
        sourceFolder = new SourceFolder(sourceFolderPath);

        git = Git.init().setDirectory(sourceFolderFile).call();

        provider = new CopyFromDirectorySourceCodeProvider(this.sourceFolderPath);
    }

    @Test
    public void shouldWorkWithEmptyRepo() throws IOException, GitAPIException {
        sourceFolder.createFiles("file1.txt");

        assertTrue(provider.isGit());

        provider.retrieveAndSaveTo(destination);

        assertNotExistsInDestination(".git");
        assertExistsInDestination("file1.txt");
    }

    @Test
    public void shouldWorkWithSubFolders() throws IOException, GitAPIException {
        sourceFolder.createFiles("subdir1/file1.txt");

        git.add().addFilepattern(".").call();
        git.commit().setMessage("commit1").call();

        sourceFolder.createFiles("subdir1/untracked.txt");
        sourceFolder.createFiles("untracked_dir/untracked.txt");

        provider.retrieveAndSaveTo(destination);

        assertExistsInDestination(
                "subdir1/file1.txt",
                "subdir1/untracked.txt",
                "untracked_dir/untracked.txt"
        );
    }


    @Test
    public void shouldHonourGitignoreContent() throws IOException, GitAPIException {
        sourceFolder.createFiles(
                "ok.txt",
                "file1.bak");
        sourceFolder.appendTo(".gitignore", "*.bak");

        git.add().addFilepattern(".").call();
        git.commit().setMessage("commit1").call();

        sourceFolder.createFiles("file2.bak");

        provider.retrieveAndSaveTo(destination);

        assertExistsInDestination(
                "ok.txt",
                ".gitignore");
        assertNotExistsInDestination(
                "file1.bak",
                "file2.bak");
    }

    @Test
    public void shouldWorkWithDeletedFiles() throws IOException, GitAPIException {
        sourceFolder.createFiles(
                "file.to.keep.txt",
                "file.to.remove.tracked.txt",
                "file.to.remove.untracked.txt");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("commit1").call();

        sourceFolder.deleteFiles("file.to.remove.tracked.txt");
        git.add().addFilepattern(".").call();

        sourceFolder.deleteFiles("file.to.remove.untracked.txt");


        provider.retrieveAndSaveTo(destination);

        assertExistsInDestination("file.to.keep.txt");
        assertNotExistsInDestination(
                "file.to.remove.tracked.txt",
                "file.to.remove.untracked.txt"
        );
    }


    @Test
    public void shouldWorkWithUncommitedFiles() throws IOException, GitAPIException {
        git.commit().setMessage("initialCommit").call();

        sourceFolder.createFiles("uncommited.txt");
        git.add().addFilepattern(".").call();

        sourceFolder.createFiles("untracked.txt");

        provider.retrieveAndSaveTo(destination);

        assertExistsInDestination(
                "uncommited.txt",
                "untracked.txt");
    }


    //~~~~~~~~~~~ Helpers
    static class SourceFolder {

        private Path sourceFolderPath;
        SourceFolder(Path sourceFolderPath) {
            this.sourceFolderPath = sourceFolderPath;
        }

        private void createFiles(String ... filesToCreate) throws IOException {
            for (String file : filesToCreate) {
                FileTestHelper.appendStringToFile(sourceFolderPath, file, "TEST");
            }
        }

        @SuppressWarnings("SameParameterValue")
        private void appendTo(String file, String content) throws IOException {
            FileTestHelper.appendStringToFile(sourceFolderPath, file, content);
        }

        private void deleteFiles(String ... filesToRemove) {
            for (String fileToRemove : filesToRemove) {
                FileTestHelper.deleteFile(sourceFolderPath, fileToRemove);
            }
        }
    }


    private void assertNotExistsInDestination(String ... filesToCheck) {
        for (String fileToCheck : filesToCheck) {
            assertFalse("File "+fileToCheck+" found in destination",
                    exists(destination, fileToCheck));
        }
        assertFalse(exists(destination, ".git"));
    }

    private void assertExistsInDestination(String ... filesToCheck) {
        for (String fileToCheck : filesToCheck) {
            assertTrue("File "+fileToCheck+" not present in destination",
                    exists(destination, fileToCheck));
        }
    }

    private static boolean exists(Path parent, String filename) {
        return Files.exists(parent.resolve(filename));
    }

}
