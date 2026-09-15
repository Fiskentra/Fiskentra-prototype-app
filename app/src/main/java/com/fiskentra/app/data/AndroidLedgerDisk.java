package com.fiskentra.app.data;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;

/** Android/Linux directory sync makes the atomic rename durable across abrupt device power loss. */
final class AndroidLedgerDisk implements PointLedger.Disk {
    private final File file;
    private final PointLedger.AtomicDisk disk;
    AndroidLedgerDisk(File file) { this.file = file; disk = new PointLedger.AtomicDisk(file); }
    public String read() throws IOException { return disk.read(); }
    public void write(String value) throws IOException {
        disk.write(value);
        FileDescriptor directory = null;
        try {
            directory = Os.open(file.getParent(), OsConstants.O_RDONLY, 0);
            if (!OsConstants.S_ISDIR(Os.fstat(directory).st_mode)) throw new IOException("Data parent is not a directory");
            Os.fsync(directory);
        } catch (ErrnoException error) { throw new IOException("Could not sync saved data directory", error); }
        finally { if (directory != null) try { Os.close(directory); } catch (ErrnoException ignored) { } }
    }
}
