package org.cryptomator.common.mount;

import org.cryptomator.common.Environment;
import org.cryptomator.common.settings.Settings;
import org.cryptomator.common.settings.VaultSettings;
import org.cryptomator.integrations.mount.Mount;
import org.cryptomator.integrations.mount.MountBuilder;
import org.cryptomator.integrations.mount.MountFailedException;
import org.cryptomator.integrations.mount.MountService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.cryptomator.integrations.mount.MountCapability.MOUNT_AS_DRIVE_LETTER;
import static org.cryptomator.integrations.mount.MountCapability.MOUNT_TO_EXISTING_DIR;
import static org.cryptomator.integrations.mount.MountCapability.MOUNT_TO_SYSTEM_CHOSEN_PATH;
import static org.cryptomator.integrations.mount.MountCapability.MOUNT_WITHIN_EXISTING_PARENT;
import static org.cryptomator.integrations.mount.MountCapability.UNMOUNT_FORCED;

@Singleton
public class Mounter {

	private static final List<String> problematicFuseMountServices = List.of("org.cryptomator.frontend.fuse.mount.MacFuseMountProvider", "org.cryptomator.frontend.fuse.mount.FuseTMountProvider");

	private final Environment env;
	private final WindowsDriveLetters driveLetters;
	private final Settings settings;
	private final List<MountService> mountProviders;
	private final AtomicReference<MountService> firstUsedProblematicFuseMountService;

	@Inject
	public Mounter(Environment env, //
				   WindowsDriveLetters driveLetters, //
				   Settings settings, List<MountService> mountProviders, //
				   @Named("FUPFMS") AtomicReference<MountService> firstUsedProblematicFuseMountService) {
		this.env = env;
		this.driveLetters = driveLetters;
		this.settings = settings;
		this.mountProviders = mountProviders;
		this.firstUsedProblematicFuseMountService = firstUsedProblematicFuseMountService;
	}

	private class SettledMounter {

		private MountService service;
		private MountBuilder builder;
		private VaultSettings vaultSettings;

		public SettledMounter(MountService service, MountBuilder builder, VaultSettings vaultSettings) {
			this.service = service;
			this.builder = builder;
			this.vaultSettings = vaultSettings;
		}

		Runnable prepare() throws IOException {
			for (var capability : service.capabilities()) {
				switch (capability) {
					case FILE_SYSTEM_NAME -> builder.setFileSystemName("cryptoFs");
					case LOOPBACK_PORT -> builder.setLoopbackPort(vaultSettings.port.get());
					case LOOPBACK_HOST_NAME -> env.getLoopbackAlias().ifPresent(builder::setLoopbackHostName);
					case READ_ONLY -> builder.setReadOnly(vaultSettings.usesReadOnlyMode.get());
					case MOUNT_FLAGS -> {
						var mountFlags = vaultSettings.mountFlags.get();
						if (mountFlags == null || mountFlags.isBlank()) {
							builder.setMountFlags(service.getDefaultMountFlags());
						} else {
							builder.setMountFlags(mountFlags);
						}
					}
					case VOLUME_ID -> builder.setVolumeId(vaultSettings.id);
					case VOLUME_NAME -> builder.setVolumeName(vaultSettings.mountName.get());
				}
			}

			return prepareMountPoint();
		}

		private Runnable prepareMountPoint() throws IOException {
			Runnable cleanup = () -> {};
			var userChosenMountPoint = vaultSettings.mountPoint.get();
			var defaultMountPointBase = env.getMountPointsDir().orElseThrow();
			var canMountToDriveLetter = service.hasCapability(MOUNT_AS_DRIVE_LETTER);
			var canMountToParent = service.hasCapability(MOUNT_WITHIN_EXISTING_PARENT);
			var canMountToDir = service.hasCapability(MOUNT_TO_EXISTING_DIR);
			var canMountToSystem = service.hasCapability(MOUNT_TO_SYSTEM_CHOSEN_PATH);

			if (userChosenMountPoint == null) {
				if (canMountToSystem) {
					// no need to set a mount point
				} else if (canMountToDriveLetter) {
					builder.setMountpoint(driveLetters.getFirstDesiredAvailable().orElseThrow()); //TODO: catch exception and translate
				} else if (canMountToParent) {
					Files.createDirectories(defaultMountPointBase);
					builder.setMountpoint(defaultMountPointBase);
				} else if (canMountToDir) {
					var mountPoint = defaultMountPointBase.resolve(vaultSettings.mountName.get());
					Files.createDirectories(mountPoint);
					builder.setMountpoint(mountPoint);
				}
			} else {
				var mpIsDriveLetter = userChosenMountPoint.toString().matches("[A-Z]:\\\\");
				if (mpIsDriveLetter) {
					if (driveLetters.getOccupied().contains(userChosenMountPoint)) {
						throw new MountPointInUseException(userChosenMountPoint);
					}
				} else if (canMountToParent && !canMountToDir) {
					MountWithinParentUtil.prepareParentNoMountPoint(userChosenMountPoint);
					cleanup = () -> {
						MountWithinParentUtil.cleanup(userChosenMountPoint);
					};
				}
				try {
					builder.setMountpoint(userChosenMountPoint);
				} catch (IllegalArgumentException | UnsupportedOperationException e) {
					var configNotSupported = (!canMountToDriveLetter && mpIsDriveLetter) //mounting as driveletter, albeit not supported
							|| (!canMountToDir && !mpIsDriveLetter) //mounting to directory, albeit not supported
							|| (!canMountToParent && !mpIsDriveLetter) //
							|| (!canMountToDir && !canMountToParent && !canMountToSystem && !canMountToDriveLetter);
					if (configNotSupported) {
						throw new MountPointNotSupportedException(userChosenMountPoint, e.getMessage());
					} else if (canMountToDir && !canMountToParent && !Files.exists(userChosenMountPoint)) {
						//mountpoint must exist
						throw new MountPointNotExistingException(userChosenMountPoint, e.getMessage());
					} else {
						//TODO: add specific exception for !canMountToDir && canMountToParent && !Files.notExists(userChosenMountPoint)
						throw new IllegalMountPointException(userChosenMountPoint, e.getMessage());
					}
				}
			}
			return cleanup;
		}

	}

	public MountHandle mount(VaultSettings vaultSettings, Path cryptoFsRoot) throws IOException, MountFailedException {
		var fallbackProvider = mountProviders.stream().findFirst().orElse(null);
		var defMntServ = mountProviders.stream().filter(s -> s.getClass().getName().equals(settings.mountService.getValue())).findFirst().orElse(fallbackProvider);
		var selMntServ = mountProviders.stream().filter(s -> s.getClass().getName().equals(vaultSettings.mountService.getValue())).findFirst().orElse(defMntServ);

		var targetIsProblematicFuse = isProblematicFuseService(selMntServ);
		if (targetIsProblematicFuse && firstUsedProblematicFuseMountService.get() == null) {
			firstUsedProblematicFuseMountService.set(selMntServ);
		}

		var fuseRestartRequired = firstUsedProblematicFuseMountService.get() != null //
				&& isProblematicFuseService(selMntServ) //
				&& !firstUsedProblematicFuseMountService.get().equals(selMntServ);

		if (fuseRestartRequired) {
			throw new FuseRestartRequiredException("fuseRestartRequired");
		}

		var builder = selMntServ.forFileSystem(cryptoFsRoot);
		var internal = new SettledMounter(selMntServ, builder, vaultSettings);
		var cleanup = internal.prepare();
		return new MountHandle(builder.mount(), selMntServ.hasCapability(UNMOUNT_FORCED), cleanup);
	}

	public static boolean isProblematicFuseService(MountService service) {
		if(service == null)
			return false;
		else
			return problematicFuseMountServices.contains(service.getClass().getName());
	}

	public record MountHandle(Mount mountObj, boolean supportsUnmountForced, Runnable specialCleanup) {

	}

}
