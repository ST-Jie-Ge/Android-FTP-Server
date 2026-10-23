package com.github.stjiege.ftpshare.filesystem;

import org.apache.ftpserver.command.impl.listing.DirectoryLister;
import org.apache.ftpserver.command.impl.listing.LISTFileFormater;
import org.apache.ftpserver.command.impl.listing.ListArgument;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import static org.junit.Assert.*;

public class LocalListingFailureTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    private LocalFileSystem view(File home) throws FtpException {
        LinkedHashMap<String, File> roots = new LinkedHashMap<>(); roots.put("", home);
        return new LocalFileSystem(new BaseUser(), false, false, roots);
    }
    private String list(LocalFileSystem view) throws IOException {
        return new DirectoryLister().listFiles(new ListArgument("/", null, new char[0]),
                new ApacheFileSystemViewAdapter(view), new LISTFileFormater());
    }
    @Test public void genuinelyEmptyHomeSucceeds() throws Exception {
        assertEquals("", list(view(files.newFolder())));
    }
    @Test public void missingHomeFailsInsteadOfLookingEmpty() throws Exception {
        LocalFileSystem view = view(new File(files.getRoot(), "missing"));
        assertFalse(view.getHomeDirectory().doesExist());
        try { list(view); fail("Missing home must fail LIST"); } catch (IOException expected) { }
    }
    @Test public void homeRemovedAfterLoginFailsListing() throws Exception {
        File home = files.newFolder(); LocalFileSystem view = view(home);
        assertTrue(view.getHomeDirectory().doesExist()); assertTrue(home.delete());
        try { list(view); fail("Unmounted/deleted home must fail LIST"); } catch (IOException expected) { }
    }
    @Test public void unreadableHomeFailsThroughApacheAdapter() throws Exception {
        File home = new File(files.newFolder().getPath()) {
            @Override public File[] listFiles() { return null; }
        };
        try { list(view(home)); fail("I/O/permission failure must fail LIST"); } catch (IOException expected) { }
    }
}
