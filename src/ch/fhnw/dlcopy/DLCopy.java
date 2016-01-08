package ch.fhnw.dlcopy;

import ch.fhnw.dlcopy.gui.DLCopyGUI;
import ch.fhnw.filecopier.CopyJob;
import ch.fhnw.filecopier.FileCopier;
import ch.fhnw.filecopier.Source;
import ch.fhnw.util.DbusTools;
import ch.fhnw.util.LernstickFileTools;
import ch.fhnw.util.MountInfo;
import ch.fhnw.util.Partition;
import ch.fhnw.util.ProcessExecutor;
import ch.fhnw.util.StorageDevice;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;
import org.freedesktop.dbus.exceptions.DBusException;

/**
 * The core class of the program
 *
 * @author Ronny Standtke <ronny.standtke@gmx.net>
 */
public class DLCopy {

    /**
     * 1024 * 1024
     */
    public static final int MEGA = 1048576;
    /**
     * all the translateable STRINGS of the program
     */
    public static final ResourceBundle STRINGS
            = ResourceBundle.getBundle("ch/fhnw/dlcopy/Strings");
    /**
     * the minimal size for a data partition (200 MByte)
     */
    public static final long MINIMUM_PARTITION_SIZE = 200 * MEGA;
    /**
     * the size of the boot partition (given in MiB)
     */
    public static final long BOOT_PARTITION_SIZE = 100;

    /**
     * Scale factor for system partition sizing.
     */
    public static final float SYSTEM_SIZE_FACTOR = 1.1f;

    // TODO: always take them from the installation source
    public static long systemSize = -1;
    public static long systemSizeEnlarged = -1;
    public static String systemPartitionLabel;

    /**
     * the known partition states for a drive
     */
    public enum PartitionState {

        /**
         * the drive is too small
         */
        TOO_SMALL,
        /**
         * the drive is so small that only a system partition can be created
         */
        ONLY_SYSTEM,
        /**
         * the system is so small that only a system and persistence partition
         * can be created
         */
        PERSISTENCE,
        /**
         * the system is large enough to create all partition scenarios
         */
        EXCHANGE
    }

    public enum DebianLiveDistribution {

        Default, lernstick, lernstick_pu
    }

    /**
     * repartition stragies
     */
    public enum RepartitionStrategy {

        /**
         * keep a partition
         */
        KEEP,
        /**
         * resize a partition
         */
        RESIZE,
        /**
         * remove a partition
         */
        REMOVE
    }

    private static final Logger LOGGER
            = Logger.getLogger(DLCopy.class.getName());
    private final static ProcessExecutor PROCESS_EXECUTOR
            = new ProcessExecutor();
    private final static long MINIMUM_FREE_MEMORY = 300 * MEGA;

    /**
     * returns the PartitionState for a given storage and system size
     *
     * @param storageSize the storage size
     * @param systemSize the system size
     * @return the PartitionState for a given storage and system size
     */
    public static PartitionState getPartitionState(
            long storageSize, long systemSize) {
        if (storageSize > (systemSize + (2 * MINIMUM_PARTITION_SIZE))) {
            return PartitionState.EXCHANGE;
        } else if (storageSize > (systemSize + MINIMUM_PARTITION_SIZE)) {
            return PartitionState.PERSISTENCE;
        } else if (storageSize > systemSize) {
            return PartitionState.ONLY_SYSTEM;
        } else {
            return PartitionState.TOO_SMALL;
        }
    }

    /**
     * moves a file
     *
     * @param source the source path
     * @param destination the destination path
     * @throws IOException if moving the file fails
     */
    public static void moveFile(String source, String destination)
            throws IOException {
        File sourceFile = new File(source);
        if (!sourceFile.exists()) {
            String errorMessage
                    = STRINGS.getString("Error_File_Does_Not_Exist");
            errorMessage = MessageFormat.format(errorMessage, source);
            throw new IOException(errorMessage);
        }
        if (!sourceFile.renameTo(new File(destination))) {
            String errorMessage = STRINGS.getString("Error_File_Move");
            errorMessage = MessageFormat.format(
                    errorMessage, source, destination);
            throw new IOException(errorMessage);
        }
    }

    /**
     * replaces a text in a file
     *
     * @param fileName the path to the file
     * @param pattern the pattern to search for
     * @param replacement the replacemtent text to set
     * @throws IOException
     */
    public static void replaceText(String fileName, Pattern pattern,
            String replacement) throws IOException {
        File file = new File(fileName);
        if (file.exists()) {
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.log(Level.INFO,
                        "replacing pattern \"{0}\" with \"{1}\" in file \"{2}\"",
                        new Object[]{pattern.pattern(), replacement, fileName});
            }
            List<String> lines = LernstickFileTools.readFile(file);
            boolean changed = false;
            for (int i = 0, size = lines.size(); i < size; i++) {
                String line = lines.get(i);
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    LOGGER.log(Level.INFO, "line \"{0}\" matches", line);
                    lines.set(i, matcher.replaceAll(replacement));
                    changed = true;
                } else {
                    LOGGER.log(Level.INFO, "line \"{0}\" does NOT match", line);
                }
            }
            if (changed) {
                writeFile(file, lines);
            }
        } else {
            LOGGER.log(Level.WARNING, "file \"{0}\" does not exist!", fileName);
        }
    }

    public static void writeFile(File file, List<String> lines)
            throws IOException {
        // delete old version of file
        if (file.exists()) {
            file.delete();
        }
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            String lineSeparator = System.getProperty("line.separator");
            for (String line : lines) {
                outputStream.write((line + lineSeparator).getBytes());
            }
            outputStream.flush();
        }
    }

    /**
     * installs syslinux from an InstallationSource to a target device
     *
     * @param source the installation source
     * @param device the device where the MBR should be installed
     * @param bootPartition the boot partition of the device, where syslinux is
     * installed
     * @throws IOException when an IOException occurs
     */
    public static void makeBootable(InstallationSource source, String device,
            Partition bootPartition) throws IOException {

        // install syslinux
        String bootDevice = "/dev/" + bootPartition.getDeviceAndNumber();
        int exitValue = source.installSyslinux(bootDevice);
        if (exitValue != 0) {
            String errorMessage = STRINGS.getString("Make_Bootable_Failed");
            errorMessage = MessageFormat.format(
                    errorMessage, bootDevice, PROCESS_EXECUTOR.getOutput());
            LOGGER.severe(errorMessage);
            throw new IOException(errorMessage);
        }

        // install MBR
        exitValue = PROCESS_EXECUTOR.executeScript(
                "cat " + source.getMbrPath() + " > " + device + '\n'
                + "sync");
        if (exitValue != 0) {
            String errorMessage = STRINGS.getString("Copying_MBR_Failed");
            errorMessage = MessageFormat.format(errorMessage, device);
            LOGGER.severe(errorMessage);
            throw new IOException(errorMessage);
        }
    }

    /**
     * Installs the installation source to a target storage device
     *
     * @param source the installation source
     * @param fileCopier the Filecopier used for copying the system partition
     * @param storageDevice the target storage device
     * @param exchangePartitionLabel the label of the exchange partition
     * @param installerOrUpgrader the Installer or Upgrader that is calling this
     * method
     * @param dlCopyGUI the program GUI
     * @throws InterruptedException when the installation was interrupted
     * @throws IOException when an I/O exception occurs
     * @throws DBusException when there was a problem with DBus
     */
    public static void copyToStorageDevice(InstallationSource source,
            FileCopier fileCopier, StorageDevice storageDevice,
            String exchangePartitionLabel,
            InstallerOrUpgrader installerOrUpgrader, DLCopyGUI dlCopyGUI)
            throws InterruptedException, IOException, DBusException {

        // determine size and state
        String device = "/dev/" + storageDevice.getDevice();
        long size = storageDevice.getSize();
        PartitionSizes partitionSizes
                = installerOrUpgrader.getPartitions(storageDevice);
        int exchangeMB = partitionSizes.getExchangeMB();
        PartitionState partitionState
                = getPartitionState(size, systemSizeEnlarged);

        boolean sdDevice = (storageDevice.getType()
                == StorageDevice.Type.SDMemoryCard);

        // determine devices
        String destinationBootDevice = null;
        String destinationExchangeDevice = null;
        String destinationDataDevice = null;
        String destinationSystemDevice;
        switch (partitionState) {
            case ONLY_SYSTEM:
                destinationBootDevice = device + (sdDevice ? "p1" : '1');
                destinationSystemDevice = device + (sdDevice ? "p2" : '2');
                break;

            case PERSISTENCE:
                destinationBootDevice = device + (sdDevice ? "p1" : '1');
                destinationDataDevice = device + (sdDevice ? "p2" : '2');
                destinationSystemDevice = device + (sdDevice ? "p3" : '3');
                break;

            case EXCHANGE:
                if (exchangeMB == 0) {
                    destinationBootDevice = device + (sdDevice ? "p1" : '1');
                    destinationDataDevice = device + (sdDevice ? "p2" : '2');
                    destinationSystemDevice = device + (sdDevice ? "p3" : '3');
                } else {
                    if (storageDevice.isRemovable()) {
                        destinationExchangeDevice
                                = device + (sdDevice ? "p1" : '1');
                        destinationBootDevice
                                = device + (sdDevice ? "p2" : '2');
                    } else {
                        destinationBootDevice
                                = device + (sdDevice ? "p1" : '1');
                        destinationExchangeDevice
                                = device + (sdDevice ? "p2" : '2');
                    }
                    if (partitionSizes.getPersistenceMB() == 0) {
                        destinationSystemDevice
                                = device + (sdDevice ? "p3" : '3');
                    } else {
                        destinationDataDevice
                                = device + (sdDevice ? "p3" : '3');
                        destinationSystemDevice
                                = device + (sdDevice ? "p4" : '4');
                    }
                }
                break;

            default:
                String errorMessage = "unsupported partitionState \""
                        + partitionState + '\"';
                LOGGER.severe(errorMessage);
                throw new IOException(errorMessage);
        }

        // create all necessary partitions
        try {
            createPartitions(storageDevice, partitionSizes, size, partitionState,
                    destinationExchangeDevice, exchangeMB,
                    exchangePartitionLabel, destinationDataDevice,
                    destinationBootDevice, destinationSystemDevice,
                    installerOrUpgrader, dlCopyGUI);
        } catch (IOException iOException) {
            // On some Corsair Flash Voyager GT drives the first sfdisk try
            // failes with the following output:
            // ---------------
            // Checking that no-one is using this disk right now ...
            // OK
            // Warning: The partition table looks like it was made
            //       for C/H/S=*/78/14 (instead of 15272/64/32).
            //
            // For this listing I'll assume that geometry.
            //
            // Disk /dev/sdc: 15272 cylinders, 64 heads, 32 sectors/track
            // Old situation:
            // Units = mebibytes of 1048576 bytes, blocks of 1024 bytes, counting from 0
            //
            //    Device Boot Start   End    MiB    #blocks   Id  System
            // /dev/sdc1         3+ 15271  15269-  15634496    c  W95 FAT32 (LBA)
            //                 start: (c,h,s) expected (7,30,1) found (1,0,1)
            //                 end: (c,h,s) expected (1023,77,14) found (805,77,14)
            // /dev/sdc2         0      -      0          0    0  Empty
            // /dev/sdc3         0      -      0          0    0  Empty
            // /dev/sdc4         0      -      0          0    0  Empty
            // New situation:
            // Units = mebibytes of 1048576 bytes, blocks of 1024 bytes, counting from 0
            //
            //    Device Boot Start   End    MiB    #blocks   Id  System
            // /dev/sdc1         0+  1023   1024-   1048575+   c  W95 FAT32 (LBA)
            // /dev/sdc2      1024  11008   9985   10224640   83  Linux
            // /dev/sdc3   * 11009  15271   4263    4365312    c  W95 FAT32 (LBA)
            // /dev/sdc4         0      -      0          0    0  Empty
            // BLKRRPART: Das Gerät oder die Ressource ist belegt
            // The command to re-read the partition table failed.
            // Run partprobe(8), kpartx(8) or reboot your system now,
            // before using mkfs
            // If you created or changed a DOS partition, /dev/foo7, say, then use dd(1)
            // to zero the first 512 bytes:  dd if=/dev/zero of=/dev/foo7 bs=512 count=1
            // (See fdisk(8).)
            // Successfully wrote the new partition table
            //
            // Re-reading the partition table ...
            // ---------------
            // Strangely, even though sfdisk exits with zero (success) the
            // partitions are *NOT* correctly created the first time. Even
            // more strangely, it always works the second time. Therefore
            // we automatically retry once more in case of an error.
            createPartitions(storageDevice, partitionSizes, size, partitionState,
                    destinationExchangeDevice, exchangeMB,
                    exchangePartitionLabel, destinationDataDevice,
                    destinationBootDevice, destinationSystemDevice,
                    installerOrUpgrader, dlCopyGUI);
        }

        // Here have to trigger a rescan of the device partitions. Otherwise
        // udisks sometimes just doesn't know about the new partitions and we
        // will later get exceptions similar to this one:
        // org.freedesktop.dbus.exceptions.DBusExecutionException:
        // No such interface 'org.freedesktop.UDisks2.Filesystem'
        PROCESS_EXECUTOR.executeProcess("partprobe", device);
        // Sigh... even after partprobe exits, we have to give udisks even more
        // time to get its act together and finally know about the new
        // partitions.
        try {
            // 5 seconds were not enough!
            TimeUnit.SECONDS.sleep(7);
        } catch (InterruptedException ex) {
            LOGGER.log(Level.SEVERE, "", ex);
        }

        // the partitions now really exist
        // -> instantiate them as objects
        Partition destinationExchangePartition
                = (destinationExchangeDevice == null) ? null
                        : Partition.getPartitionFromDeviceAndNumber(
                                destinationExchangeDevice.substring(5),
                                systemSize);

        Partition destinationDataPartition
                = (destinationDataDevice == null) ? null
                        : Partition.getPartitionFromDeviceAndNumber(
                                destinationDataDevice.substring(5), systemSize);

        Partition destinationBootPartition
                = Partition.getPartitionFromDeviceAndNumber(
                        destinationBootDevice.substring(5), systemSize);

        Partition destinationSystemPartition
                = Partition.getPartitionFromDeviceAndNumber(
                        destinationSystemDevice.substring(5), systemSize);

        // copy operating system files
        copyExchangeBootAndSystem(source, fileCopier, storageDevice,
                destinationExchangePartition, destinationBootPartition,
                destinationSystemPartition, installerOrUpgrader, dlCopyGUI);

        // copy persistence layer
        copyPersistence(source, installerOrUpgrader,
                destinationDataPartition, dlCopyGUI);

        // make storage device bootable
        installerOrUpgrader.showWritingBootSector();
        makeBootable(source, device, destinationBootPartition);

        if (!umount(destinationBootPartition, dlCopyGUI)) {
            String errorMessage = "could not umount destination boot partition";
            throw new IOException(errorMessage);
        }

        if (!umount(destinationSystemPartition, dlCopyGUI)) {
            String errorMessage
                    = "could not umount destination system partition";
            throw new IOException(errorMessage);
        }
        source.unmountTmpPartitions();
    }

    /**
     * returns the partitions sizes for a StorageDevice when installing
     *
     * @param storageDevice the StorageDevice to check
     * @param exchangePartitionSize the planned size of the exchange partition
     * @return the partitions sizes for a StorageDevice when installing
     */
    public static PartitionSizes getInstallPartitionSizes(
            StorageDevice storageDevice, int exchangePartitionSize) {
        return getPartitionSizes(storageDevice, false,
                null, 0, exchangePartitionSize);
    }

    /**
     * returns the partitions sizes for a StorageDevice when upgrading
     *
     * @param storageDevice the StorageDevice to check
     * @param exchangeRepartitionStrategy the repartitioning strategy for the
     * exchange partition
     * @param resizedExchangePartitionSize the new size of the exchange
     * partition if we want to resize it
     * @return the partitions sizes for a StorageDevice when upgrading
     */
    public static PartitionSizes getUpgradePartitionSizes(
            StorageDevice storageDevice,
            RepartitionStrategy exchangeRepartitionStrategy,
            int resizedExchangePartitionSize) {
        return getPartitionSizes(storageDevice, true,
                exchangeRepartitionStrategy, resizedExchangePartitionSize, 0);
    }

    // TODO: use installation source for calculation
    private static PartitionSizes getPartitionSizes(StorageDevice storageDevice,
            boolean upgrading, RepartitionStrategy upgradeRepartitionStrategy,
            int upgradeResizedExchangePartitionSize,
            int installExchangePartitionSize) {
        long size = storageDevice.getSize();
        long overhead = size
                - (BOOT_PARTITION_SIZE * MEGA) - systemSizeEnlarged;
        int overheadMB = (int) (overhead / MEGA);
        PartitionState partitionState = getPartitionState(
                size, systemSizeEnlarged);
        switch (partitionState) {
            case TOO_SMALL:
                return null;

            case ONLY_SYSTEM:
                return new PartitionSizes(0, 0);

            case EXCHANGE:
                int exchangeMB = 0;
                if (upgrading) {
                    switch (upgradeRepartitionStrategy) {
                        case KEEP:
                            Partition exchangePartition
                                    = storageDevice.getExchangePartition();
                            if (exchangePartition != null) {
                                LOGGER.log(Level.INFO, "exchangePartition: {0}",
                                        exchangePartition);
                                exchangeMB = (int) (exchangePartition.getSize()
                                        / MEGA);
                            }
                            break;
                        case RESIZE:
                            exchangeMB = upgradeResizedExchangePartitionSize;
                        // stays at 0 MB in all other cases...
                    }
                } else {
                    exchangeMB = installExchangePartitionSize;
                }
                LOGGER.log(Level.INFO, "exchangeMB = {0}", exchangeMB);
                int persistenceMB = overheadMB - exchangeMB;
                return new PartitionSizes(exchangeMB, persistenceMB);

            case PERSISTENCE:
                return new PartitionSizes(0, overheadMB);

            default:
                LOGGER.log(Level.SEVERE,
                        "unsupported partitionState \"{0}\"", partitionState);
                return null;
        }
    }

    private static void createPartitions(StorageDevice storageDevice,
            PartitionSizes partitionSizes, long storageDeviceSize,
            final PartitionState partitionState, String exchangeDevice,
            int exchangeMB, String exchangePartitionLabel,
            String persistenceDevice, String bootDevice, String systemDevice,
            InstallerOrUpgrader installerOrUpgrader, DLCopyGUI dlCopyGUI)
            throws InterruptedException, IOException, DBusException {

        // update GUI
        installerOrUpgrader.showCreatingFileSystems();

        String device = "/dev/" + storageDevice.getDevice();

        // determine exact partition sizes
        long overhead = storageDeviceSize - systemSizeEnlarged;
        int persistenceMB = partitionSizes.getPersistenceMB();
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "size of {0} = {1} Byte\n"
                    + "overhead = {2} Byte\n"
                    + "exchangeMB = {3} MiB\n"
                    + "persistenceMB = {4} MiB",
                    new Object[]{device, storageDeviceSize, overhead,
                        exchangeMB, persistenceMB
                    });
        }

        // assemble partition command
        List<String> partedCommandList = new ArrayList<>();
        partedCommandList.add("/sbin/parted");
        partedCommandList.add("-s");
        partedCommandList.add("-a");
        partedCommandList.add("optimal");
        partedCommandList.add(device);

//            // list of "rm" commands must be inversely sorted, otherwise
//            // removal of already existing partitions will fail when storage
//            // device has logical partitions in extended partitions (the logical
//            // partitions are no longer found when the extended partition is
//            // already removed)
//            List<String> partitionNumbers = new ArrayList<String>();
//            for (Partition partition : storageDevice.getPartitions()) {
//                partitionNumbers.add(String.valueOf(partition.getNumber()));
//            }
//            Collections.sort(partitionNumbers);
//            for (int i = partitionNumbers.size() - 1; i >=0; i--) {
//                partedCommandList.add("rm");
//                partedCommandList.add(partitionNumbers.get(i));
//            }
        switch (partitionState) {
            case ONLY_SYSTEM:
                // create two partitions: boot, system
                String bootBorder = BOOT_PARTITION_SIZE + "MiB";
                mkpart(partedCommandList, "0%", bootBorder);
                mkpart(partedCommandList, bootBorder, "100%");
                setFlag(partedCommandList, "1", "boot", "on");
                setFlag(partedCommandList, "1", "lba", "on");
                break;

            case PERSISTENCE:
                // create three partitions: boot, persistence, system
                bootBorder = BOOT_PARTITION_SIZE + "MiB";
                String persistenceBorder
                        = (BOOT_PARTITION_SIZE + persistenceMB) + "MiB";
                mkpart(partedCommandList, "0%", bootBorder);
                mkpart(partedCommandList, bootBorder, persistenceBorder);
                mkpart(partedCommandList, persistenceBorder, "100%");
                setFlag(partedCommandList, "1", "boot", "on");
                setFlag(partedCommandList, "1", "lba", "on");
                break;

            case EXCHANGE:
                if (exchangeMB == 0) {
                    // create three partitions: boot, persistence, system
                    bootBorder = BOOT_PARTITION_SIZE + "MiB";
                    persistenceBorder
                            = (BOOT_PARTITION_SIZE + persistenceMB) + "MiB";
                    mkpart(partedCommandList, "0%", bootBorder);
                    mkpart(partedCommandList, bootBorder, persistenceBorder);
                    mkpart(partedCommandList, persistenceBorder, "100%");
                    setFlag(partedCommandList, "1", "boot", "on");
                    setFlag(partedCommandList, "1", "lba", "on");

                } else {
                    String secondBorder;
                    if (storageDevice.isRemovable()) {
                        String exchangeBorder = exchangeMB + "MiB";
                        bootBorder = (exchangeMB + BOOT_PARTITION_SIZE) + "MiB";
                        secondBorder = bootBorder;
                        mkpart(partedCommandList, "0%", exchangeBorder);
                        mkpart(partedCommandList, exchangeBorder, bootBorder);
                        setFlag(partedCommandList, "2", "boot", "on");
                    } else {
                        bootBorder = BOOT_PARTITION_SIZE + "MiB";
                        String exchangeBorder
                                = (BOOT_PARTITION_SIZE + exchangeMB) + "MiB";
                        secondBorder = exchangeBorder;
                        mkpart(partedCommandList, "0%", bootBorder);
                        mkpart(partedCommandList, bootBorder, exchangeBorder);
                        setFlag(partedCommandList, "1", "boot", "on");
                    }
                    if (persistenceMB == 0) {
                        mkpart(partedCommandList, secondBorder, "100%");
                    } else {
                        persistenceBorder = (BOOT_PARTITION_SIZE
                                + exchangeMB + persistenceMB) + "MiB";
                        mkpart(partedCommandList,
                                secondBorder, persistenceBorder);
                        mkpart(partedCommandList, persistenceBorder, "100%");
                    }
                    setFlag(partedCommandList, "1", "lba", "on");
                    setFlag(partedCommandList, "2", "lba", "on");
                }
                break;

            default:
                String errorMessage = "unsupported partitionState \""
                        + partitionState + '\"';
                LOGGER.severe(errorMessage);
                throw new IOException(errorMessage);
        }

        // safety wait in case of device scanning
        // 5 seconds were not enough...
        TimeUnit.SECONDS.sleep(7);

        // check if a swap partition is active on this device
        // if so, switch it off
        List<String> swaps
                = LernstickFileTools.readFile(new File("/proc/swaps"));
        for (String swapLine : swaps) {
            if (swapLine.startsWith(device)) {
                swapoffPartition(device, swapLine, dlCopyGUI);
            }
        }

        // umount all mounted partitions of device
        umountPartitions(device, dlCopyGUI);

        // Create a new partition table before creating the partitions,
        // otherwise USB flash drives previously written with a dd'ed ISO
        // will NOT work!
        //
        // NOTE 1:
        // "parted <device> mklabel msdos" did NOT work correctly here!
        // (the partition table type was still unknown and booting failed)
        //
        // NOTE 2:
        // "--print-reply" is needed in the call to dbus-send below to make
        // the call synchronous
        int exitValue;
        if (DbusTools.DBUS_VERSION == DbusTools.DbusVersion.V1) {
            exitValue = PROCESS_EXECUTOR.executeProcess("dbus-send",
                    "--system", "--print-reply",
                    "--dest=org.freedesktop.UDisks",
                    "/org/freedesktop/UDisks/devices/" + device.substring(5),
                    "org.freedesktop.UDisks.Device.PartitionTableCreate",
                    "string:mbr", "array:string:");

        } else {
            // Even more fun with udisks2! :-)
            //
            // Now whe have to call org.freedesktop.UDisks2.Block.Format
            // This function has the signature 'sa{sv}'.
            // dbus-send is unable to send messages with this signature.
            // To quote the dbus-send manpage:
            // ****************************
            //  D-Bus supports more types than these, but dbus-send currently
            //  does not. Also, dbus-send does not permit empty containers or
            //  nested containers (e.g. arrays of variants).
            // ****************************
            //
            // creating a Java interface also fails, see here:
            // https://bugs.debian.org/cgi-bin/bugreport.cgi?bug=777241
            //
            // So we have to create a script that calls python.
            // This utterly sucks but our options are limited...
            // exitValue = processExecutor.executeScript(true, true,
            //         "python -c 'import dbus; "
            //         + "dbus.SystemBus().call_blocking("
            //         + "\"org.freedesktop.UDisks2\", "
            //         + "\"/org/freedesktop/UDisks2/block_devices/"
            //         + device.substring(5) + "\", "
            //         + "\"org.freedesktop.UDisks2.Block\", "
            //         + "\"Format\", \"sa{sv}\", (\"dos\", {}))'");
            //
            // It gets even better. The call above very often just fails with
            // the following error message:
            // Traceback (most recent call last):
            // File "<string>", line 1, in <module>
            // File "/usr/lib/python2.7/dist-packages/dbus/connection.py", line 651, in call_blocking message, timeout)
            // dbus.exceptions.DBusException: org.freedesktop.UDisks2.Error.Failed: Error synchronizing after initial wipe: Timed out waiting for object
            //
            // So, for Debian 8 we retry with good old parted and hope for the
            // best...
            exitValue = PROCESS_EXECUTOR.executeProcess(true, true,
                    "parted", "-s", device, "mklabel", "msdos");
        }
        if (exitValue != 0) {
            String errorMessage
                    = STRINGS.getString("Error_Creating_Partition_Table");
            errorMessage = MessageFormat.format(errorMessage, device);
            LOGGER.severe(errorMessage);
            throw new IOException(errorMessage);
        }

        // another safety wait...
        TimeUnit.SECONDS.sleep(3);

        // repartition device
        String[] commandArray = partedCommandList.toArray(
                new String[partedCommandList.size()]);
        exitValue = PROCESS_EXECUTOR.executeProcess(commandArray);
        if (exitValue != 0) {
            String errorMessage = STRINGS.getString("Error_Repartitioning");
            errorMessage = MessageFormat.format(errorMessage, device);
            LOGGER.severe(errorMessage);
            throw new IOException(errorMessage);
        }

        // safety wait so that new partitions are known to the system
        TimeUnit.SECONDS.sleep(7);

        // The partition types assigned by parted are mosty garbage.
        // We must fix them here...
        // The boot partition is actually formatted with FAT32, but "hidden"
        // by using the EFI partition type.
        switch (partitionState) {
            case ONLY_SYSTEM:
                // create two partitions:
                //  1) boot (EFI)
                //  2) system (Linux)
                PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                        "--id", device, "1", "ef");
                PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                        "--id", device, "2", "83");
                break;

            case PERSISTENCE:
                // create three partitions:
                //  1) boot (EFI)
                //  2) persistence (Linux)
                //  3) system (Linux)
                PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                        "--id", device, "1", "ef");
                PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                        "--id", device, "2", "83");
                PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                        "--id", device, "3", "83");
                break;

            case EXCHANGE:
                if (exchangeMB == 0) {
                    // create three partitions:
                    //  1) boot (EFI)
                    //  2) persistence (Linux)
                    //  3) system (Linux)
                    PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                            "--id", device, "1", "ef");
                    PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                            "--id", device, "2", "83");
                    PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                            "--id", device, "3", "83");
                } else {
                    // determine ID for exchange partition
                    String exchangePartitionID;
                    String fileSystem
                            = installerOrUpgrader.getExhangePartitionFileSystem();
                    if (fileSystem.equalsIgnoreCase("fat32")) {
                        exchangePartitionID = "c";
                    } else {
                        // exFAT & NTFS
                        exchangePartitionID = "7";
                    }

                    if (storageDevice.isRemovable()) {
                        //  1) exchange (exFAT, FAT32 or NTFS)
                        //  2) boot (EFI)
                        PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                                "--id", device, "1", exchangePartitionID);
                        PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                                "--id", device, "2", "ef");
                    } else {
                        //  1) boot (EFI)
                        //  2) exchange (exFAT, FAT32 or NTFS)
                        PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                                "--id", device, "1", "ef");
                        PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                                "--id", device, "2", exchangePartitionID);
                    }

                    if (persistenceMB == 0) {
                        //  3) system (Linux)
                        PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                                "--id", device, "3", "83");
                    } else {
                        //  3) persistence (Linux)
                        //  4) system (Linux)
                        PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                                "--id", device, "3", "83");
                        PROCESS_EXECUTOR.executeProcess("/sbin/sfdisk",
                                "--id", device, "4", "83");
                    }
                }
                break;

            default:
                String errorMessage = "unsupported partitionState \""
                        + partitionState + '\"';
                LOGGER.log(Level.SEVERE, errorMessage);
                throw new IOException(errorMessage);
        }

        // create file systems
        switch (partitionState) {
            case ONLY_SYSTEM:
                formatBootAndSystemPartition(bootDevice, systemDevice);
                return;

            case PERSISTENCE:
                formatPersistencePartition(persistenceDevice,
                        installerOrUpgrader.getDataPartitionFileSystem(),
                        dlCopyGUI);
                formatBootAndSystemPartition(bootDevice, systemDevice);
                return;

            case EXCHANGE:
                if (exchangeMB != 0) {
                    // create file system for exchange partition
                    formatExchangePartition(exchangeDevice,
                            exchangePartitionLabel,
                            installerOrUpgrader.getExhangePartitionFileSystem());
                }
                if (persistenceDevice != null) {
                    formatPersistencePartition(persistenceDevice,
                            installerOrUpgrader.getDataPartitionFileSystem(),
                            dlCopyGUI);
                }
                formatBootAndSystemPartition(bootDevice, systemDevice);
                return;

            default:
                String errorMessage = "unsupported partitionState \""
                        + partitionState + '\"';
                LOGGER.log(Level.SEVERE, errorMessage);
                throw new IOException(errorMessage);
        }
    }

    private static void copyExchangeBootAndSystem(InstallationSource source,
            FileCopier fileCopier, StorageDevice storageDevice,
            Partition destinationExchangePartition,
            Partition destinationBootPartition,
            Partition destinationSystemPartition,
            InstallerOrUpgrader installerOrUpgrader, DLCopyGUI dlCopyGUI)
            throws InterruptedException, IOException, DBusException {

        // define CopyJob for exchange paritition
        String destinationExchangePath = null;
        CopyJob exchangeCopyJob = null;
        if (installerOrUpgrader instanceof Installer) {
            Installer installer = (Installer) installerOrUpgrader;
            if (installer.isCopyExchangePartitionSelected()) {
                destinationExchangePath
                        = destinationExchangePartition.mount().getMountPath();
                exchangeCopyJob = new CopyJob(
                        new Source[]{source.getExchangeCopySource()},
                        new String[]{destinationExchangePath});
            }
        }

        // define CopyJobs for boot and system parititions
        CopyJobsInfo copyJobsInfo = prepareBootAndSystemCopyJobs(source,
                storageDevice, destinationBootPartition,
                destinationExchangePartition, destinationSystemPartition,
                installerOrUpgrader.getExhangePartitionFileSystem());

        // copy all files
        installerOrUpgrader.showCopyingFiles(fileCopier);

        CopyJob bootFilesCopyJob = copyJobsInfo.getBootFilesCopyJob();
        fileCopier.copy(exchangeCopyJob, bootFilesCopyJob,
                copyJobsInfo.getBootCopyJob(), copyJobsInfo.getSystemCopyJob());

        if (bootFilesCopyJob != null) {
            // The exchange partition is FAT32 on a removable media and
            // therefore we copied the boot files not only to the boot partition
            // but also to the exchange partition.

            if (destinationExchangePath == null) {
                // The user did not select to copy the exchange partition but it
                // was already mounted in prepareBootAndSystemCopyJobs().
                // Just get the reference here...
                destinationExchangePath
                        = destinationExchangePartition.getMountPath();
            }

            // hide boot files
            hideBootFiles(bootFilesCopyJob, destinationExchangePath);

            // change data partition mode (if needed)
            if (installerOrUpgrader instanceof Installer) {
                Installer installer = (Installer) installerOrUpgrader;
                DataPartitionMode dataPartitionMode
                        = installer.getDataPartitionMode();
                setDataPartitionMode(source, dataPartitionMode,
                        destinationExchangePath);
            }
        }

        // update GUI
        installerOrUpgrader.showUnmounting();

        source.unmountTmpPartitions();
        if (destinationExchangePath != null) {
            destinationExchangePartition.umount();
        }

        String destinationBootPath = copyJobsInfo.getDestinationBootPath();
        // isolinux -> syslinux renaming
        // !!! don't check here for boot storage device type !!!
        // (usb flash drives with an isohybrid image also contain the
        //  isolinux directory)
        isolinuxToSyslinux(destinationBootPath, dlCopyGUI);

        // change data partition mode on target (if needed)
        if (installerOrUpgrader instanceof Installer) {
            Installer installer = (Installer) installerOrUpgrader;
            DataPartitionMode dataPartitionMode
                    = installer.getDataPartitionMode();
            setDataPartitionMode(source, dataPartitionMode, destinationBootPath);
        }
    }

    private static void copyPersistence(InstallationSource source,
            InstallerOrUpgrader installerOrUpgrader,
            Partition destinationDataPartition, DLCopyGUI dlCopyGUI)
            throws IOException, InterruptedException, DBusException {

        // some early checks and returns...
        if (!(installerOrUpgrader instanceof Installer)) {
            return;
        }
        Installer installer = (Installer) installerOrUpgrader;
        if (!installer.isCopyDataPartitionSelected()) {
            return;
        }
        if (destinationDataPartition == null) {
            return;
        }

        // mount persistence source
        MountInfo sourceDataMountInfo = source.getDataPartition().mount();
        String sourceDataPath = sourceDataMountInfo.getMountPath();
        if (sourceDataPath == null) {
            String errorMessage = "could not mount source data partition";
            throw new IOException(errorMessage);
        }

        // mount persistence destination
        MountInfo destinationDataMountInfo = destinationDataPartition.mount();
        String destinationDataPath = destinationDataMountInfo.getMountPath();
        if (destinationDataPath == null) {
            String errorMessage = "could not mount destination data partition";
            throw new IOException(errorMessage);
        }

        // TODO: use filecopier as soon as it supports symlinks etc.
        copyPersistenceCp(installer, sourceDataPath,
                destinationDataPath, dlCopyGUI);

        // update GUI
        dlCopyGUI.showInstallUnmounting();

        // umount both source and destination persistence partitions
        //  (only if there were not mounted before)
        if (!sourceDataMountInfo.alreadyMounted()) {
            source.getDataPartition().umount();
        }
        if (!destinationDataMountInfo.alreadyMounted()) {
            destinationDataPartition.umount();
        }
    }

    private static void mkpart(List<String> commandList,
            String start, String end) {
        commandList.add("mkpart");
        commandList.add("primary");
        commandList.add(start);
        commandList.add(end);
    }

    private static void setFlag(List<String> commandList,
            String partition, String flag, String value) {
        commandList.add("set");
        commandList.add(partition);
        commandList.add(flag);
        commandList.add(value);
    }

    private static void formatExchangePartition(String device,
            String label, String fileSystem) throws IOException {

        // create file system for exchange partition
        String mkfsBuilder;
        String mkfsLabelSwitch;
        String quickSwitch = null;
        if (fileSystem.equalsIgnoreCase("fat32")) {
            mkfsBuilder = "vfat";
            mkfsLabelSwitch = "-n";
        } else if (fileSystem.equalsIgnoreCase("exfat")) {
            mkfsBuilder = "exfat";
            mkfsLabelSwitch = "-n";
        } else {
            mkfsBuilder = "ntfs";
            quickSwitch = "-f";
            mkfsLabelSwitch = "-L";
        }

        int exitValue;
        if (quickSwitch == null) {
            exitValue = PROCESS_EXECUTOR.executeProcess(
                    "/sbin/mkfs." + mkfsBuilder, mkfsLabelSwitch,
                    label, device);
        } else {
            exitValue = PROCESS_EXECUTOR.executeProcess(
                    "/sbin/mkfs." + mkfsBuilder, quickSwitch, mkfsLabelSwitch,
                    label, device);
        }

        if (exitValue != 0) {
            String errorMessage
                    = STRINGS.getString("Error_Create_Exchange_Partition");
            errorMessage = MessageFormat.format(errorMessage, device);
            LOGGER.severe(errorMessage);
            throw new IOException(errorMessage);
        }
    }

    private static void copyPersistenceCp(Installer installer,
            String persistenceSourcePath, String persistenceDestinationPath,
            DLCopyGUI dlCopyGUI)
            throws InterruptedException, IOException {
        // this needs to be a script because of the shell globbing
        String copyScript = "#!/bin/bash\n"
                + "cp -av \"" + persistenceSourcePath + "/\"* \""
                + persistenceDestinationPath + "/\"";
        dlCopyGUI.showInstallPersistencyCopy(
                installer, copyScript, persistenceSourcePath);
    }

    /**
     * unmounts a device or mountpoint
     *
     * @param deviceOrMountpoint the device or mountpoint to unmount
     * @throws IOException
     */
    public static void umount(String deviceOrMountpoint, DLCopyGUI dlCopyGUI)
            throws IOException {
        // check if a swapfile is in use on this partition
        List<String> mounts = LernstickFileTools.readFile(
                new File("/proc/mounts"));
        for (String mount : mounts) {
            String[] tokens = mount.split(" ");
            String device = tokens[0];
            String mountPoint = tokens[1];
            if (device.equals(deviceOrMountpoint)
                    || mountPoint.equals(deviceOrMountpoint)) {
                List<String> swapLines = LernstickFileTools.readFile(
                        new File("/proc/swaps"));
                for (String swapLine : swapLines) {
                    if (swapLine.startsWith(mountPoint)) {
                        // deactivate swapfile
                        swapoffFile(device, swapLine, dlCopyGUI);
                    }
                }
            }
        }

        int exitValue = PROCESS_EXECUTOR.executeProcess(
                "umount", deviceOrMountpoint);
        if (exitValue != 0) {
            String errorMessage = STRINGS.getString("Error_Umount");
            errorMessage = MessageFormat.format(
                    errorMessage, deviceOrMountpoint);
            LOGGER.severe(errorMessage);
            throw new IOException(errorMessage);
        }
    }

    /**
     * unmounts a given partition
     *
     * @param partition the given partition
     * @param dlCopyGUI the GUI to show error messages
     * @return <tt>true</tt>, if unmounting was successfull, <tt>false</tt>
     * otherwise
     * @throws DBusException
     */
    public static boolean umount(Partition partition, DLCopyGUI dlCopyGUI)
            throws DBusException {
        // early return
        if (!partition.isMounted()) {
            LOGGER.log(Level.INFO, "{0} was NOT mounted...",
                    partition.getDeviceAndNumber());
            return true;
        }

        if (partition.umount()) {
            LOGGER.log(Level.INFO, "{0} was successfully umounted",
                    partition.getDeviceAndNumber());
            return true;
        } else {
            String errorMessage = STRINGS.getString("Error_Umount");
            errorMessage = MessageFormat.format(errorMessage,
                    "/dev/" + partition.getDeviceAndNumber());
            dlCopyGUI.showErrorMessage(errorMessage);
            return false;
        }
    }

    /**
     * writes the currently used version of persistence.conf into a given path
     *
     * @param mountPath the given path
     * @throws IOException
     */
    public static void writePersistenceConf(String mountPath)
            throws IOException {
        try (FileWriter writer = new FileWriter(
                mountPath + "/persistence.conf")) {
            writer.write("/ union,source=.\n");
            writer.flush();
        }
    }

    /**
     * formats and tunes the persistence partition of a given device (e.g.
     * "/dev/sdb1") and creates the default persistence configuration file on
     * the partition file system
     *
     * @param device the given device (e.g. "/dev/sdb1")
     * @param fileSystem the file system to use
     * @param dlCopyGUI the program GUI to show error messages
     * @throws DBusException if a DBusException occurs
     * @throws IOException if an IOException occurs
     */
    public static void formatPersistencePartition(
            String device, String fileSystem, DLCopyGUI dlCopyGUI)
            throws DBusException, IOException {

        // make sure that the partition is unmounted
        if (isMounted(device)) {
            umount(device, dlCopyGUI);
        }

        // If we want to create a partition at the exact same location of
        // another type of partition mkfs becomes interactive.
        // For instance if we first install with an exchange partition and later
        // without one, mkfs asks the following question:
        // ------------
        // /dev/sda2 contains a exfat file system labelled 'Austausch'
        // Proceed anyway? (y,n)
        // ------------
        // To make a long story short, this is the reason we have to use the
        // force flag "-F" here.
        int exitValue = PROCESS_EXECUTOR.executeProcess("/sbin/mkfs."
                + fileSystem, "-F", "-L", Partition.PERSISTENCE_LABEL, device);
        if (exitValue != 0) {
            LOGGER.severe(PROCESS_EXECUTOR.getOutput());
            String errorMessage = STRINGS.getString(
                    "Error_Create_Data_Partition");
            LOGGER.severe(errorMessage);
            throw new IOException(errorMessage);
        }

        // tuning
        exitValue = PROCESS_EXECUTOR.executeProcess(
                "/sbin/tune2fs", "-m", "0", "-c", "0", "-i", "0", device);
        if (exitValue != 0) {
            LOGGER.severe(PROCESS_EXECUTOR.getOutput());
            String errorMessage = STRINGS.getString(
                    "Error_Tune_Data_Partition");
            LOGGER.severe(errorMessage);
            throw new IOException(errorMessage);
        }

        // We have to wait a little for dbus to get to know the new filesystem.
        // Otherwise we will sometimes get the following exception in the calls
        // below:
        // org.freedesktop.dbus.exceptions.DBusExecutionException:
        // No such interface 'org.freedesktop.UDisks2.Filesystem'
        try {
            TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException ex) {
            LOGGER.log(Level.SEVERE, "", ex);
        }

        // create default persistence configuration file
        Partition persistencePartition
                = Partition.getPartitionFromDeviceAndNumber(
                        device.substring(5), systemSize);
        String mountPath = persistencePartition.mount().getMountPath();
        if (mountPath == null) {
            throw new IOException("could not mount persistence partition");
        }
        writePersistenceConf(mountPath);
        persistencePartition.umount();
    }

    private static void umountPartitions(String device, DLCopyGUI dlCopyGUI) 
            throws IOException {
        LOGGER.log(Level.FINEST, "umountPartitions({0})", device);
        List<String> mounts
                = LernstickFileTools.readFile(new File("/proc/mounts"));
        for (String mount : mounts) {
            String mountedPartition = mount.split(" ")[0];
            if (mountedPartition.startsWith(device)) {
                umount(mountedPartition, dlCopyGUI);
            }
        }
    }

    private static boolean isMounted(String device) throws IOException {
        List<String> mounts
                = LernstickFileTools.readFile(new File("/proc/mounts"));
        for (String mount : mounts) {
            String mountedPartition = mount.split(" ")[0];
            if (mountedPartition.startsWith(device)) {
                return true;
            }
        }
        return false;
    }

    private static void swapoffFile(String device, String swapLine,
            DLCopyGUI dlCopyGUI) throws IOException {

        SwapInfo swapInfo = new SwapInfo(swapLine);
        String swapFile = swapInfo.getFile();
        long remainingFreeMem = swapInfo.getRemainingFreeMemory();

        boolean disableSwap = true;
        if (remainingFreeMem < MINIMUM_FREE_MEMORY) {
            // deactivating the swap file is dangerous
            // show a warning dialog and let the user decide
            String warningMessage = STRINGS.getString("Warning_Swapoff_File");
            String freeMem = LernstickFileTools.getDataVolumeString(
                    remainingFreeMem, 0);
            warningMessage = MessageFormat.format(
                    warningMessage, swapFile, device, freeMem);
            disableSwap = dlCopyGUI.showConfirmDialog(
                    STRINGS.getString("Warning"), warningMessage);
        }

        if (disableSwap) {
            int exitValue = PROCESS_EXECUTOR.executeProcess(
                    "swapoff", swapFile);
            if (exitValue != 0) {
                String errorMessage = STRINGS.getString("Error_Swapoff_File");
                errorMessage = MessageFormat.format(errorMessage, swapFile);
                LOGGER.severe(errorMessage);
                throw new IOException(errorMessage);
            }
        }
    }

    private static void swapoffPartition(String device, String swapLine,
            DLCopyGUI dlCopyGUI) throws IOException {

        SwapInfo swapInfo = new SwapInfo(swapLine);
        String swapFile = swapInfo.getFile();
        long remainingFreeMem = swapInfo.getRemainingFreeMemory();

        boolean disableSwap = true;
        if (remainingFreeMem < MINIMUM_FREE_MEMORY) {
            // deactivating the swap file is dangerous
            // show a warning dialog and let the user decide
            String warningMessage
                    = STRINGS.getString("Warning_Swapoff_Partition");
            String freeMem = LernstickFileTools.getDataVolumeString(
                    remainingFreeMem, 0);
            warningMessage = MessageFormat.format(
                    warningMessage, swapFile, device, freeMem);
            disableSwap = dlCopyGUI.showConfirmDialog(
                    STRINGS.getString("Warning"), warningMessage);
        }

        if (disableSwap) {
            int exitValue = PROCESS_EXECUTOR.executeProcess(
                    "swapoff", swapFile);
            if (exitValue != 0) {
                String errorMessage
                        = STRINGS.getString("Error_Swapoff_Partition");
                errorMessage = MessageFormat.format(errorMessage, swapFile);
                LOGGER.severe(errorMessage);
                throw new IOException(errorMessage);
            }
        }
    }

    /**
     * creates a CopyJobsInfo for a given source / destination combination
     *
     * @param source the installation source
     * @param storageDevice the destination StorageDevice
     * @param destinationBootPartition the destination boot partition
     * @param destinationExchangePartition the destination exchange partition
     * @param destinationSystemPartition the destination system partition
     * @param destinationExchangePartitionFileSystem the file system of the
     * destination exchange partition
     * @return the CopyJobsInfo for the given source / destination combination
     * @throws DBusException
     */
    public static CopyJobsInfo prepareBootAndSystemCopyJobs(
            InstallationSource source,
            StorageDevice storageDevice,
            Partition destinationBootPartition,
            Partition destinationExchangePartition,
            Partition destinationSystemPartition,
            String destinationExchangePartitionFileSystem)
            throws DBusException {

        String destinationBootPath
                = destinationBootPartition.mount().getMountPath();
        String destinationSystemPath
                = destinationSystemPartition.mount().getMountPath();

        Source bootCopyJobSource = source.getBootCopySource();
        Source systemCopyJobSource = source.getSystemCopySource();

        CopyJob bootCopyJob = new CopyJob(
                new Source[]{bootCopyJobSource},
                new String[]{destinationBootPath});

        // Only if we have a FAT32 exchange partition on a removable media we
        // have to copy the boot files also to the exchange partition.
        CopyJob bootFilesCopyJob = null;
        if ((destinationExchangePartition != null)
                && (storageDevice.isRemovable())) {
            if ("fat32".equalsIgnoreCase(
                    destinationExchangePartitionFileSystem)) {
                String destinationExchangePath
                        = destinationExchangePartition.mount().getMountPath();
                bootFilesCopyJob = new CopyJob(
                        new Source[]{source.getExchangeBootCopySource()},
                        new String[]{destinationExchangePath});
            }
        }

        CopyJob systemCopyJob = new CopyJob(
                new Source[]{systemCopyJobSource},
                new String[]{destinationSystemPath});

        return new CopyJobsInfo(destinationBootPath, destinationSystemPath,
                bootCopyJob, bootFilesCopyJob, systemCopyJob);
    }

    /**
     * tries to hide the boot files on the exchange partition with FAT
     * attributes (works on Windows) and a .hidden file (works on OS X)
     *
     * @param bootFilesCopyJob the CopyJob for the boot file, used to get the
     * list of files to hide
     * @param destinationExchangePath
     */
    public static void hideBootFiles(
            CopyJob bootFilesCopyJob, String destinationExchangePath) {

        Source bootFilesSource = bootFilesCopyJob.getSources()[0];
        String[] bootFiles = bootFilesSource.getBaseDirectory().list();

        if (bootFiles == null) {
            return;
        }

        // use FAT attributes to hide boot files in Windows
        for (String bootFile : bootFiles) {
            Path destinationPath = Paths.get(destinationExchangePath, bootFile);
            if (Files.exists(destinationPath)) {
                PROCESS_EXECUTOR.executeProcess(
                        "fatattr", "+h", destinationPath.toString());
            }
        }

        // use ".hidden" file to hide boot files in OS X
        String osxHiddenFilePath = destinationExchangePath + "/.hidden";
        try (FileWriter fileWriter = new FileWriter(osxHiddenFilePath)) {
            String lineSeperator = System.lineSeparator();
            for (String bootFile : bootFiles) {
                Path destinationPath = Paths.get(
                        destinationExchangePath, bootFile);
                if (Files.exists(destinationPath)) {
                    fileWriter.write(bootFile + lineSeperator);
                }
            }
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "", ex);
        }

        // use FAT attributes again to hide OS X ".hidden" file in Windows
        PROCESS_EXECUTOR.executeProcess("fatattr", "+h", osxHiddenFilePath);
    }

    /**
     * converts an isolinux directory structure to a syslinux directory
     * structure
     *
     * @param mountPoint the mountpoint of the isolinux directory
     * @param dlCopyGUI the GUI to show error messages
     * @throws IOException
     */
    public static void isolinuxToSyslinux(String mountPoint,
            DLCopyGUI dlCopyGUI) throws IOException {
        final String isolinuxPath = mountPoint + "/isolinux";
        if (new File(isolinuxPath).exists()) {
            LOGGER.info("replacing isolinux with syslinux");
            final String syslinuxPath = mountPoint + "/syslinux";
            moveFile(isolinuxPath, syslinuxPath);
            moveFile(syslinuxPath + "/isolinux.cfg",
                    syslinuxPath + "/syslinux.cfg");

            // replace "isolinux" with "syslinux" in some files
            Pattern pattern = Pattern.compile("isolinux");
            replaceText(syslinuxPath + "/exithelp.cfg", pattern, "syslinux");
            replaceText(syslinuxPath + "/stdmenu.cfg", pattern, "syslinux");
            replaceText(syslinuxPath + "/syslinux.cfg", pattern, "syslinux");

            // remove boot.cat
            String bootCatFileName = syslinuxPath + "/boot.cat";
            File bootCatFile = new File(bootCatFileName);
            if (!bootCatFile.delete()) {
                dlCopyGUI.showErrorMessage(
                        "Could not delete " + bootCatFileName);
            }

            // update md5sum.txt
            String md5sumFileName = mountPoint + "/md5sum.txt";
            replaceText(md5sumFileName, pattern, "syslinux");
            File md5sumFile = new File(md5sumFileName);
            if (md5sumFile.exists()) {
                List<String> lines = LernstickFileTools.readFile(md5sumFile);
                for (int i = lines.size() - 1; i >= 0; i--) {
                    String line = lines.get(i);
                    if (line.contains("xmlboot.config")
                            || line.contains("grub.cfg")) {
                        lines.remove(i);
                    }
                }
                writeFile(md5sumFile, lines);
                PROCESS_EXECUTOR.executeProcess("sync");
            } else {
                LOGGER.log(Level.WARNING,
                        "file \"{0}\" does not exist!", md5sumFileName);
            }

        } else {
            // boot device is probably a hard disk
            LOGGER.info("isolinux directory does not exist -> no renaming");
        }
    }

    /**
     * formats the boot and system partition
     *
     * @param bootDevice the boot device
     * @param systemDevice the system device
     * @throws IOException
     */
    public static void formatBootAndSystemPartition(
            String bootDevice, String systemDevice) throws IOException {

        int exitValue = PROCESS_EXECUTOR.executeProcess(
                "/sbin/mkfs.vfat", "-n", Partition.BOOT_LABEL, bootDevice);
        if (exitValue != 0) {
            LOGGER.severe(PROCESS_EXECUTOR.getOutput());
            String errorMessage
                    = STRINGS.getString("Error_Create_Boot_Partition");
            LOGGER.severe(errorMessage);
            throw new IOException(errorMessage);
        }

        exitValue = PROCESS_EXECUTOR.executeProcess(
                "/sbin/mkfs.ext4", "-L", systemPartitionLabel, systemDevice);
        if (exitValue != 0) {
            LOGGER.severe(PROCESS_EXECUTOR.getOutput());
            String errorMessage
                    = STRINGS.getString("Error_Create_System_Partition");
            LOGGER.severe(errorMessage);
            throw new IOException(errorMessage);
        }
    }

    /**
     * sets the data partition mode on a target system
     *
     * @param source the installation source
     * @param dataPartitionMode the data partition mode to set
     * @param imagePath the path where the target image is mounted
     */
    public static void setDataPartitionMode(InstallationSource source,
            DataPartitionMode dataPartitionMode, String imagePath) {
        LOGGER.log(Level.INFO,
                "data partition mode of installation source: {0}",
                source.getDataPartitionMode());
        LOGGER.log(Level.INFO,
                "selected data partition mode for destination: {0}",
                dataPartitionMode);
        if (source.getDataPartitionMode() != dataPartitionMode) {
            BootConfigUtil.setDataPartitionMode(dataPartitionMode, imagePath);
        }
    }

    public static RepartitionStrategy getRepartitionStrategy(
            boolean keep, boolean resize) {
        if (keep) {
            return RepartitionStrategy.KEEP;
        }
        if (resize) {
            return RepartitionStrategy.RESIZE;
        }
        return RepartitionStrategy.REMOVE;
    }
}
