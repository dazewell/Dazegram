package com.radolyn.ayugram.downloads;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import tw.nekomimi.nekogram.helpers.ChatsHelper;
import xyz.nextalone.nagram.NaConfig;

public final class DownloadFolderHelper {

    private static final String MIME_TYPE_DIRECTORY = DocumentsContract.Document.MIME_TYPE_DIR;
    private static final int PERMISSION_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private DownloadFolderHelper() {
    }

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(NaConfig.INSTANCE.getDownloadFolderUri().String());
    }

    public static String getDisplayName() {
        String name = NaConfig.INSTANCE.getDownloadFolderName().String();
        return TextUtils.isEmpty(name) ? LocaleController.getString(R.string.DownloadFolderSelected) : name;
    }

    public static boolean setFolder(Uri treeUri, int grantedFlags) {
        if (treeUri == null) {
            return false;
        }
        ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        int flags = grantedFlags & PERMISSION_FLAGS;
        if ((flags & PERMISSION_FLAGS) != PERMISSION_FLAGS) {
            return false;
        }
        Uri oldUri = getTreeUri();
        try {
            resolver.takePersistableUriPermission(treeUri, flags);
            String name = queryDisplayName(resolver, getRootDocumentUri(treeUri));
            if (TextUtils.isEmpty(name)) {
                if (!treeUri.equals(oldUri)) {
                    resolver.releasePersistableUriPermission(treeUri, flags);
                }
                return false;
            }
            if (oldUri != null && !oldUri.equals(treeUri)) {
                releasePermission(resolver, oldUri);
            }
            NaConfig.INSTANCE.getDownloadFolderUri().setConfigString(treeUri.toString());
            NaConfig.INSTANCE.getDownloadFolderName().setConfigString(name);
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            if (!treeUri.equals(oldUri)) {
                releasePermission(resolver, treeUri);
            }
            return false;
        }
    }

    public static void clearFolder() {
        ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        Uri uri = getTreeUri();
        if (uri != null) {
            releasePermission(resolver, uri);
        }
        NaConfig.INSTANCE.getDownloadFolderUri().setConfigString("");
        NaConfig.INSTANCE.getDownloadFolderName().setConfigString("");
    }

    public static Session createSession() {
        Uri treeUri = getTreeUri();
        if (treeUri == null) {
            if (isConfigured()) {
                clearInvalidFolder();
            }
            return null;
        }
        ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        try {
            if (!hasWritePermission(resolver, treeUri)) {
                clearInvalidFolder();
                return null;
            }
            if (TextUtils.isEmpty(queryDisplayName(resolver, getRootDocumentUri(treeUri)))) {
                showUnavailableFolder();
                return null;
            }
        } catch (Exception e) {
            FileLog.e(e);
            showUnavailableFolder();
            return null;
        }
        return new Session(resolver, treeUri);
    }

    private static void clearInvalidFolder() {
        clearFolder();
        showUnavailableFolder();
    }

    private static void showUnavailableFolder() {
        AndroidUtilities.runOnUIThread(() -> BulletinFactory.global()
                .createErrorBulletin(LocaleController.getString(R.string.DownloadFolderUnavailable))
                .show());
    }

    private static Uri getTreeUri() {
        String value = NaConfig.INSTANCE.getDownloadFolderUri().String();
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        try {
            Uri uri = Uri.parse(value);
            return DocumentsContract.isTreeUri(uri) ? uri : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasWritePermission(ContentResolver resolver, Uri treeUri) {
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (treeUri.equals(permission.getUri()) && permission.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    private static void releasePermission(ContentResolver resolver, Uri uri) {
        int flags = 0;
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (uri.equals(permission.getUri())) {
                if (permission.isReadPermission()) {
                    flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
                }
                if (permission.isWritePermission()) {
                    flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                }
            }
        }
        if (flags == 0) {
            return;
        }
        try {
            resolver.releasePersistableUriPermission(uri, flags);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static Uri getRootDocumentUri(Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    public static final class Session {

        private final ContentResolver resolver;
        private final Uri treeUri;
        private final Uri rootUri;
        private final Map<String, Uri> folders = new HashMap<>();

        private Session(ContentResolver resolver, Uri treeUri) {
            this.resolver = resolver;
            this.treeUri = treeUri;
            rootUri = getRootDocumentUri(treeUri);
        }

        public Uri saveFile(File sourceFile, String filename, String mimeType, MessageObject messageObject, BooleanSupplier cancelled, IntConsumer progress) {
            if (sourceFile == null || !sourceFile.exists()) {
                return null;
            }
            String outputName = TextUtils.isEmpty(filename) ? sourceFile.getName() : filename;
            Uri parent = rootUri;
            if (messageObject != null && NaConfig.INSTANCE.getSaveToChatSubfolder().Bool()) {
                String folderName = ChatsHelper.getChatFolderName(messageObject);
                parent = getOrCreateFolder(folderName);
                if (parent == null) {
                    return null;
                }
            }

            Uri destination = null;
            boolean copyCancelled = false;
            try (FileInputStream input = new FileInputStream(sourceFile)) {
                if (isInternalFile(input.getFD()) || cancelled != null && cancelled.getAsBoolean()) {
                    return null;
                }
                destination = DocumentsContract.createDocument(resolver, parent, resolveMimeType(outputName, mimeType), outputName);
                if (destination == null) {
                    return null;
                }
                try (OutputStream output = resolver.openOutputStream(destination, "w")) {
                    if (output == null) {
                        throw new IllegalStateException("Unable to open selected download folder");
                    }
                    byte[] buffer = new byte[32 * 1024];
                    long copied = 0;
                    long size = sourceFile.length();
                    int lastProgress = -1;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (cancelled != null && cancelled.getAsBoolean()) {
                            copyCancelled = true;
                            break;
                        }
                        output.write(buffer, 0, read);
                        copied += read;
                        if (progress != null && size > 0) {
                            int currentProgress = (int) (copied * 100 / size);
                            if (currentProgress != lastProgress) {
                                progress.accept(currentProgress);
                                lastProgress = currentProgress;
                            }
                        }
                    }
                }
                if (copyCancelled) {
                    delete(destination);
                    return null;
                }
                return destination;
            } catch (Exception e) {
                FileLog.e(e);
                if (destination != null) {
                    delete(destination);
                }
                return null;
            }
        }

        public Uri saveGeneratedFile(String filename, String mimeType, MessageObject messageObject, BooleanSupplier cancelled, OutputWriter writer) {
            Uri parent = rootUri;
            if (messageObject != null && NaConfig.INSTANCE.getSaveToChatSubfolder().Bool()) {
                parent = getOrCreateFolder(ChatsHelper.getChatFolderName(messageObject));
                if (parent == null) {
                    return null;
                }
            }
            Uri destination = null;
            try {
                destination = DocumentsContract.createDocument(resolver, parent, mimeType, filename);
                if (destination == null) {
                    return null;
                }
                try (OutputStream output = resolver.openOutputStream(destination, "w")) {
                    if (output == null) {
                        throw new IllegalStateException("Unable to open selected download folder");
                    }
                    writer.write(output);
                }
                if (cancelled != null && cancelled.getAsBoolean()) {
                    delete(destination);
                    return null;
                }
                return destination;
            } catch (Exception e) {
                FileLog.e(e);
                if (destination != null) {
                    delete(destination);
                }
                return null;
            }
        }

        private Uri getOrCreateFolder(String name) {
            if (TextUtils.isEmpty(name)) {
                return rootUri;
            }
            Uri cached = folders.get(name);
            if (cached != null) {
                return cached;
            }
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(rootUri));
            try (Cursor cursor = resolver.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null)) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        if (name.equals(cursor.getString(1)) && MIME_TYPE_DIRECTORY.equals(cursor.getString(2))) {
                            Uri folder = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0));
                            folders.put(name, folder);
                            return folder;
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
                return null;
            }
            try {
                Uri folder = DocumentsContract.createDocument(resolver, rootUri, MIME_TYPE_DIRECTORY, name);
                if (folder != null) {
                    folders.put(name, folder);
                }
                return folder;
            } catch (Exception e) {
                FileLog.e(e);
                return null;
            }
        }

        private void delete(Uri uri) {
            try {
                DocumentsContract.deleteDocument(resolver, uri);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

    }

    public interface OutputWriter {
        void write(OutputStream output) throws Exception;
    }

    private static String resolveMimeType(String filename, String fallback) {
        String extension = FileLoader.getFileExtension(new File(filename));
        if (!TextUtils.isEmpty(extension)) {
            String value = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return TextUtils.isEmpty(fallback) ? "application/octet-stream" : fallback;
    }

    private static boolean isInternalFile(FileDescriptor descriptor) {
        try {
            @SuppressWarnings("DiscouragedPrivateApi")
            Method getInt = FileDescriptor.class.getDeclaredMethod("getInt$");
            return AndroidUtilities.isInternalUri((Integer) getInt.invoke(descriptor));
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }
}
