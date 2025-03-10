package org.primftpd.filesystem;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import androidx.annotation.Nullable;

public final class StorageManagerUtil {
    private static final String PRIMARY_VOLUME_NAME = "primary";

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageManagerUtil.class);

    public static String getFullDocIdPathFromTreeUri(@Nullable final Uri treeUri, Context context) {
        if (treeUri == null) {
            return null;
        }
        String volumePath = getVolumePath(getVolumeIdFromTreeUri(treeUri), context);
        if (volumePath == null) {
            return File.separator;
        }
        if (volumePath.endsWith(File.separator)) {
            volumePath = volumePath.substring(0, volumePath.length() - 1);
        }

        String documentPath = getDocumentPathFromTreeUri(treeUri);
        if (documentPath.endsWith(File.separator)) {
            documentPath = documentPath.substring(0, documentPath.length() - 1);
        }

        if (documentPath.length() > 0) {
            if (documentPath.startsWith(File.separator)) {
                return volumePath + documentPath;
            } else {
                return volumePath + File.separator + documentPath;
            }
        } else {
            return volumePath;
        }
    }

    public static String getVolumePathFromTreeUri(@Nullable final Uri treeUri, Context context) {
        if (treeUri == null) {
            return null;
        }
        String volumePath = getVolumePath(getVolumeIdFromTreeUri(treeUri), context);
        if (volumePath == null) {
            return null;
        }
        if (! volumePath.endsWith(File.separator)) {
            volumePath += File.separator;
        }
        return volumePath;
    }

    public static int getFilesystemTimeResolutionForTreeUri(Uri startUrl) {
        String volumeId = getVolumeIdFromTreeUri(startUrl);
        return getFilesystemTimeResolutionForVolumeId(volumeId);
    }

    private final static Map<String, Integer> CACHED_FILESYSTEM_TIME_RESOLUTIONS = new HashMap<>();

    /**
     * This function is to handle 2 Android bugs.
     * <p>
     * 1. Android caches the modification times for files, ie. when we read back the modification time of a freshly created file,
     *    Andorid will lie and return the value the file was asked to be saved, and not the value the real file-system was able to store,
     *    so we have to figure out whether the underlying file-system is an SD-card related and use the file-system specific resolution.
     * <p>
     * 2. Even when the SD-card is mounted at /mnt/media_rw/XXXX-XXXX with the proper file-system, when it is mounted at /storage/XXXX-XXXX,
     *    the used sdcardfs has another bug, it provides the same 2s resolution even for the exfat file-system,
     *    so in this case we have to modify the resolution even for the exfat file-system to 2s.
     *
     * @param  volumeId volume id, eg. "1234-ABCD"
     * @return          file system's time resolution measured in milliseconds
     */
    public static int getFilesystemTimeResolutionForVolumeId(String volumeId) {
        LOGGER.trace("getFilesystemTimeResolutionForVolumeId({})", volumeId);
        int timeResolution = 1; // use 1ms by default
        if (volumeId != null) {
            Integer cachedTimeResolution = CACHED_FILESYSTEM_TIME_RESOLUTIONS.get(volumeId);
            if (cachedTimeResolution != null) {
                timeResolution = cachedTimeResolution;
                LOGGER.trace("  used cached value ({})", timeResolution);
            } else {
                int mediaTimeResolution = 0;
                int storageTimeResolution = 0;
                String mediaMountPoint = "/mnt/media_rw/" + volumeId;
                String mediaOption = null;
                String storageMountPoint = "/storage/" + volumeId;
                try(BufferedReader br = new BufferedReader(new FileReader("/proc/mounts"))) {
                    // sample contents for /proc/mounts
                    // /dev/block/vold/public:xxx,xx /mnt/media_rw/XXXX-XXXX vfat ... 0 0                     -> 2000 ms
                    // /dev/block/vold/public:xxx,xx /mnt/media_rw/XXXX-XXXX sdfat ...,fs=vfat:16,... 0 0     -> 2000 ms
                    // /dev/block/vold/public:xxx,xx /mnt/media_rw/XXXX-XXXX sdfat ...,fs=vfat:32,... 0 0     -> 2000 ms
                    // /dev/block/vold/public:xxx,xx /mnt/media_rw/XXXX-XXXX sdfat ...,fs=exfat,... 0 0       ->   10 ms
                    // /mnt/media_rw/XXXX-XXXX /storage/XXXX-XXXX sdcardfs ... 0 0                            -> 2000 ms
                    for (String line; (line = br.readLine()) != null; ) {
                        LOGGER.trace("  {}", line);
                        String[] mountInformations = line.split(" ");
                        if (mountInformations.length >= 4) {
                            if (mediaTimeResolution == 0 && mountInformations[1].equals(mediaMountPoint)) {
                                if (mountInformations[2].equals("vfat")) {
                                    mediaTimeResolution = 2000;
                                } else if (mountInformations[2].equals("sdfat")) {
                                    mediaTimeResolution = 2000; // use 2000ms by default
                                    for (String option : mountInformations[3].split(",")) {
                                        if (option.startsWith("fs=")) {
                                            mediaOption = option;
                                            if (option.startsWith("fs=vfat")) {
                                                mediaTimeResolution = 2000;
                                            } else if (option.startsWith("fs=exfat")) {
                                                mediaTimeResolution = 10;
                                            }
                                            break;
                                        }
                                    }
                                } else {
                                    mediaTimeResolution = 1;
                                }
                                if (mediaOption == null) {
                                    LOGGER.trace("    found media mount point {} with type {} -> {}ms", new Object[]{mountInformations[1], mountInformations[2], mediaTimeResolution});
                                } else {
                                    LOGGER.trace("    found media mount point {} with type {} with option {} -> {}ms", new Object[]{mountInformations[1], mountInformations[2], mediaOption, mediaTimeResolution});
                                }
                            }
                            if (storageTimeResolution == 0 && mountInformations[1].equals(storageMountPoint)) {
                                if (mountInformations[2].equals("sdcardfs")) {
                                    storageTimeResolution = 2000;
                                } else {
                                    storageTimeResolution = 1;
                                }
                                LOGGER.trace("    found storage mount point {} with type {} -> {}ms", new Object[]{mountInformations[1], mountInformations[2], storageTimeResolution});
                            }
                            if (mediaTimeResolution != 0 && storageTimeResolution != 0) {
                                break;
                            }
                        }
                    }
                    timeResolution = Math.max(timeResolution, Math.max(mediaTimeResolution, storageTimeResolution));
                    CACHED_FILESYSTEM_TIME_RESOLUTIONS.put(volumeId, timeResolution);
                } catch (Exception e) {
                    LOGGER.error("getFilesystemTimeResolutionForVolumeId()", e);
                }
            }
        }
        LOGGER.trace("  getFilesystemTimeResolutionForVolumeId({}) -> {}", volumeId, timeResolution);
        return timeResolution;
    }

    @SuppressLint("ObsoleteSdkInt")
    private static String getVolumePath(final String volumeId, Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null;
        }
        try {
            StorageManager mStorageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            Class<?> storageVolumeClass = Class.forName("android.os.storage.StorageVolume");
            Method getVolumeList = mStorageManager.getClass().getMethod("getVolumeList");
            Method getUuid = storageVolumeClass.getMethod("getUuid");
            Method getPath = storageVolumeClass.getMethod("getPath");
            Method isPrimary = storageVolumeClass.getMethod("isPrimary");
            Object result = getVolumeList.invoke(mStorageManager);

            final int length = result != null ? Array.getLength(result) : 0;
            for (int i = 0; i < length; i++) {
                Object storageVolumeElement = Array.get(result, i);
                String uuid = (String) getUuid.invoke(storageVolumeElement);
                Boolean primaryObj = (Boolean) isPrimary.invoke(storageVolumeElement);
                boolean primary = primaryObj != null && primaryObj;

                // primary volume?
                if (primary && PRIMARY_VOLUME_NAME.equals(volumeId)) {
                    return (String) getPath.invoke(storageVolumeElement);
                }

                // other volumes?
                if (uuid != null && uuid.equals(volumeId)) {
                    return (String) getPath.invoke(storageVolumeElement);
                }
            }
        } catch (Exception ex) {
            LOGGER.error("", ex);
        }
        return null;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static String getVolumeIdFromTreeUri(final Uri treeUri) {
        try {
            final String docId = DocumentsContract.getTreeDocumentId(treeUri);
            final String[] split = docId.split(":");
            if (split.length > 0) {
                return split[0];
            }
        } catch (Exception ex) {
            LOGGER.error("", ex);
        }
        return null;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static String getDocumentPathFromTreeUri(final Uri treeUri) {
        try {
            final String docId = DocumentsContract.getDocumentId(treeUri);
            final String[] split = docId.split(":");
            if ((split.length >= 2) && (split[1] != null)) {
                return split[1];
            }
        } catch (Exception ex) {
            LOGGER.error("", ex);
        }
        return File.separator;
    }
}
