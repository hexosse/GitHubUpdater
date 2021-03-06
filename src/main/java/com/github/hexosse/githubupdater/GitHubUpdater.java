package com.github.hexosse.githubupdater;


/*
 * Copyright 2016 Hexosse
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

/**
 * Check for updates on GitHub for a gien repository.
 *
 * (This is an update version of Gravity plugin that was design for bukkit)
 *
 * @author hexoose
 * @version 1.0
 */

@SuppressWarnings("unused")
public class GitHubUpdater {

    /* Constants */

    // Remote file's download link
    private static final String LINK_VALUE = "browser_download_url";
    // Remote file's release type
    private static final String TYPE_PRERELEASE_VALUE = "prerelease";
    private static final String TYPE_DRAFT_VALUE = "draft";
    // Remote file's build version
    private static final String VERSION_VALUE = "tag_name";
    // Remote file's build version
    private static final String ASSETS_VALUE = "assets";
    // Path to GET
    private static final String QUERY = "/repos/{{ REPOSITORY }}/releases";
    // Slugs will be appended to this to get to the project's RSS feed
    private static final String HOST = "https://api.github.com";
    // User-agent when querying Curse
    private static final String USER_AGENT = "hexosse";
    // If the version number contains one of these, don't update.
    private static final String[] NO_UPDATE_TAG = { "-DEV", "-PRE", "-SNAPSHOT" };
    // Used for downloading files
    private static final int BYTE_SIZE = 1024;
    // Config key for disabling Updater
    private static final String DISABLE_CONFIG_KEY = "disable";
    // Default disable value in config
    private static final boolean DISABLE_DEFAULT = false;

    /* User-provided variables */

    // Plugin running Updater
    private final Plugin plugin;
    // Type of update check to run
    private final UpdateType type;
    // Whether to announce file downloads
    private final boolean announce;
    // The plugin file (jar)
    private final File file;
    // The folder that downloads will be placed in
    private final File updateFolder;
    // The provided callback (if any)
    private final UpdateCallback callback;
    // GitHub repository
    private final String repository;
    // The plugin version
    private final Version current;

    /* Collected from GitHub */

    private Version version;
    private String versionName;
    private String versionLink;
    private ReleaseType versionType;
    private String versionLatest;

    /* Update process variables */

    // Connection to RSS
    private URL url;
    // Updater thread
    private Thread thread;
    // Used for determining the outcome of the update process
    private GitHubUpdater.UpdateResult result = GitHubUpdater.UpdateResult.SUCCESS;

    /**
     * Gives the developer the result of the update process. Can be obtained by called {@link #getResult()}
     */
    public enum UpdateResult {
        /**
         * The updater found an update, and has readied it to be loaded the next time the server restarts/reloads.
         */
        SUCCESS,
        /**
         * The updater did not find an update, and nothing was downloaded.
         */
        NO_UPDATE,
        /**
         * The server administrator has disabled the updating system.
         */
        DISABLED,
        /**
         * The updater found an update, but was unable to download it.
         */
        FAIL_DOWNLOAD,
        /**
         * For some reason, the updater was unable to contact dev.bukkit.org to download the file.
         */
        FAIL_DBO,
        /**
         * When running the version check, the file on DBO did not contain a recognizable version.
         */
        FAIL_NOVERSION,
        /**
         * The id provided by the plugin running the updater was invalid and doesn't exist on DBO.
         */
        FAIL_BADID,
        /**
         * The server administrator has improperly configured their API key in the configuration.
         */
        FAIL_API,
        /**
         * The updater found an update, but because of the UpdateType being set to NO_DOWNLOAD, it wasn't downloaded.
         */
        UPDATE_AVAILABLE
    }

    /**
     * Allows the developer to specify the type of update that will be run.
     */
    public enum UpdateType {
        /**
         * Run a version check, and then if the file is out of date, download the newest version.
         */
        DEFAULT,
        /**
         * Don't run a version check, just find the latest update and download it.
         */
        NO_VERSION_CHECK,
        /**
         * Get information about the version and the download size, but don't actually download anything.
         */
        NO_DOWNLOAD
    }

    /**
     * Represents the various release types of a file on BukkitDev.
     */
    public enum ReleaseType {
        /**
         * An "draft" file.
         */
        DRAFT,
        /**
         * A "prerelease" file.
         */
        PRERELEASE,
        /**
         * A "release" file.
         */
        RELEASE
    }

    /**
     * Simple major.minor.patch storage system with getters.
     */
    public static class Version
    {
        /**
         * Pattern used to match semantic versioning compliant strings.
         * <br>
         * Major: matcher.group(1) Minor: matcher.group(2) Patch: matcher.group(3)
         * <br>
         * Does detect suffixes such as RC though they're unused as of now.
         */
        protected static Pattern regex = Pattern.compile("(?:[v]?)([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+)?", Pattern.CASE_INSENSITIVE);

        /**
         * Store the version major.
         */
        private int major;

        /**
         * Store the version minor.
         */
        private int minor;

        /**
         * Store the version patch.
         */
        private int patch;

        /**
         * Create a new instance of the {@link Version} class.
         *
         * @param major semver major
         * @param minor semver minor
         * @param patch semver patch
         */
        public Version(int major, int minor, int patch) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
        }

        /**
         * Create a new instance of the {@link Version} class.
         *
         * @param version version string
         */
        public Version(String version)
        {
            Version parseVersion = Version.parse(version);
            if(parseVersion!=null) {
                this.major = parseVersion.major;
                this.minor = parseVersion.minor;
                this.patch = parseVersion.patch;
            }
        }

        /**
         * @return semver major
         */
        public int getMajor() {
            return major;
        }

        /**
         * @return semver minor
         */
        public int getMinor() {
            return minor;
        }

        /**
         * @return semver patch
         */
        public int getPatch() {
            return patch;
        }

        /**
         * @return joined version string.
         */
        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }

        /**
         * Quick method for parsing version strings and matching them using the
         * {@link java.util.regex.Pattern} in {@link GitHubUpdater}
         *
         * @param version semver string to parse
         * @return {@link Version} if valid semver string
         */
        public static Version parse(String version)
        {
            Matcher matcher = regex.matcher(version);

            if (matcher.matches()) {
                int x = Integer.parseInt(matcher.group(1));
                int y = Integer.parseInt(matcher.group(2));
                int z = Integer.parseInt(matcher.group(3));

                return new Version(x, y, z);
            }

            return null;
        }

        /**
         * Test if the version string contains a valid semver string
         *
         * @param version version to test
         * @return true if valid
         */
        public static boolean isSemver(String version) {
            return regex.matcher(version).matches();
        }

        /**
         * Test if the version string contains a valid semver string
         *
         * @return true if valid
         */
        public boolean isSemver() {
            return Version.isSemver(this.toString());
        }

        /**
         * Little method to see if the input version is greater than ours.
         *
         * @param version input {@link Version} object
         * @return true if the version is greater than ours
         */
        public boolean equals(Version version)
        {
            if(version.getMajor() != this.getMajor())   return false;
            if(version.getMinor() != this.getMinor())   return false;
            if(version.getPatch() != this.getPatch())   return false;
            return true;
        }

        /**
         * Little method to see if the input version is greater than ours.
         *
         * @param version input {@link Version} object
         * @return true if the version is greater than ours
         */
        public boolean compare(Version version)
        {
            int result = version.getMajor() - this.getMajor();
            if (result == 0) {
                result = version.getMinor() - this.getMinor();
                if (result == 0) {
                    result = version.getPatch() - this.getPatch();
                }
            }
            return result > 0;
        }
    }

    /**
     * Initialize the updater.
     *
     * @param plugin        The plugin that is checking for an update.
     * @param repository    The GitHub repository thay store the project.
     * @param file          The file that the plugin is running from, get this by doing this.getFile() from within your main class.
     * @param type          Specify the type of update this will be. See {@link UpdateType}
     * @param announce      True if the program should announce the progress of new updates in console.
     */
    public GitHubUpdater(Plugin plugin, String repository, File file, UpdateType type, boolean announce) {
        this(plugin, repository, file, type, null, announce);
    }

    /**
     * Initialize the updater with the provided callback.
     *
     * @param plugin        The plugin that is checking for an update.
     * @param repository    The GitHub repository thay store the project.
     * @param file          The file that the plugin is running from, get this by doing this.getFile() from within your main class.
     * @param type          Specify the type of update this will be. See {@link UpdateType}
     * @param callback      The callback instance to notify when the Updater has finished
     */
    public GitHubUpdater(Plugin plugin, String repository, File file, UpdateType type, UpdateCallback callback) {
        this(plugin, repository, file, type, callback, false);
    }

    /**
     * Initialize the updater with the provided callback.
     *
     * @param plugin        The plugin that is checking for an update.
     * @param repository    The GitHub repository thay store the project.
     * @param file          The file that the plugin is running from, get this by doing this.getFile() from within your main class.
     * @param type          Specify the type of update this will be. See {@link UpdateType}
     * @param callback      The callback instance to notify when the Updater has finished
     * @param announce      True if the program should announce the progress of new updates in console.
     */
    public GitHubUpdater(Plugin plugin, String repository, File file, UpdateType type, UpdateCallback callback, boolean announce)
    {
        this.plugin = plugin;
        this.type = type;
        this.announce = announce;
        this.file = file;
        this.repository = repository;
        this.updateFolder = this.plugin.getServer().getUpdateFolderFile();
        this.current = Version.parse(this.plugin.getDescription().getVersion());
        this.callback = callback;

        try
        {
            this.url = new URL(((String)(GitHubUpdater.HOST + GitHubUpdater.QUERY)).replace("{{ REPOSITORY }}", this.repository));
        } catch (NumberFormatException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Unable to parse semver string.", e);
            this.result = UpdateResult.FAIL_API;
        } catch (final MalformedURLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Invalid URL, return failed response.", e);
            this.result = UpdateResult.FAIL_API;
        }

        if (this.result != UpdateResult.FAIL_API) {
            this.thread = new Thread(new UpdateRunnable());
            this.thread.start();
        } else {
            runUpdater();
        }
    }

    /**
     * Get the result of the update process.
     *
     * @return result of the update process.
     * @see UpdateResult
     */
    public GitHubUpdater.UpdateResult getResult() {
        this.waitForThread();
        return this.result;
    }

    /**
     * Get the latest version's release type.
     *
     * @return latest version's release type.
     * @see ReleaseType
     */
    public ReleaseType getLatestType() {
        this.waitForThread();
        if (this.versionType != null) {
            return this.versionType;
        }
        return null;
    }

    /**
     * Get the latest version's game version (such as "CB 1.2.5-R1.0").
     *
     * @return latest version's game version.
     */
    public String getLatestVersion() {
        this.waitForThread();
        return this.versionLatest;
    }

    /**
     * Get the latest version's direct file link.
     *
     * @return latest version's file link.
     */
    public String getLatestFileLink() {
        this.waitForThread();
        return this.versionLink;
    }

    /**
     * As the result of Updater output depends on the thread's completion, it is necessary to wait for the thread to finish
     * before allowing anyone to check the result.
     */
    private void waitForThread() {
        if ((this.thread != null) && this.thread.isAlive()) {
            try {
                this.thread.join();
            } catch (final InterruptedException e) {
                this.plugin.getLogger().log(Level.SEVERE, null, e);
            }
        }
    }

    /**
     * Save an update from hexosse.github.com into the server's update folder.
     *
     * @param file the name of the file to save it as.
     */
    private void saveFile(String file)
    {
        final File folder = this.updateFolder;

        deleteOldFiles();
        if (!folder.exists()) {
            this.fileIOOrError(folder, folder.mkdir(), true);
        }
        downloadFile();

        // Check to see if it's a zip file, if it is, unzip it.
        final File dFile = new File(folder.getAbsolutePath(), file);
        if (dFile.getName().endsWith(".zip")) {
            // Unzip
            this.unzip(dFile.getAbsolutePath());
        }
        if (this.announce) {
            this.plugin.getLogger().info("Finished updating.");
        }
    }

    /**
     * Download a file and save it to the specified folder.
     */
    private void downloadFile()
    {
        BufferedInputStream in = null;
        FileOutputStream fout = null;
        try {
            URL fileUrl = new URL(this.versionLink);
            String fileName = fileUrl.toString().substring(fileUrl.toString().lastIndexOf("/") + 1);
            final int fileLength = fileUrl.openConnection().getContentLength();
            in = new BufferedInputStream(fileUrl.openStream());
            fout = new FileOutputStream(new File(this.updateFolder, fileName));

            final byte[] data = new byte[GitHubUpdater.BYTE_SIZE];
            int count;
            if (this.announce) {
                this.plugin.getLogger().info("About to download a new update: " + this.versionLatest);
            }
            long downloaded = 0;
            while ((count = in.read(data, 0, GitHubUpdater.BYTE_SIZE)) != -1) {
                downloaded += count;
                fout.write(data, 0, count);
                final int percent = (int) ((downloaded * 100) / fileLength);
                if (this.announce && ((percent % 10) == 0)) {
                    this.plugin.getLogger().info("Downloading update: " + percent + "% of " + fileLength + " bytes.");
                }
            }
        } catch (Exception ex) {
            this.plugin.getLogger().log(Level.WARNING, "The auto-updater tried to download a new update, but was unsuccessful.", ex);
            this.result = GitHubUpdater.UpdateResult.FAIL_DOWNLOAD;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (final IOException ex) {
                this.plugin.getLogger().log(Level.SEVERE, null, ex);
            }
            try {
                if (fout != null) {
                    fout.close();
                }
            } catch (final IOException ex) {
                this.plugin.getLogger().log(Level.SEVERE, null, ex);
            }
        }
    }

    /**
     * Remove possibly leftover files from the update folder.
     */
    private void deleteOldFiles() {
        //Just a quick check to make sure we didn't leave any files from last time...
        File[] list = listFilesOrError(this.updateFolder);
        for (final File xFile : list) {
            if (xFile.getName().endsWith(".zip")) {
                this.fileIOOrError(xFile, xFile.mkdir(), true);
            }
        }
    }

    /**
     * Part of Zip-File-Extractor, modified by Gravity for use with Updater.
     *
     * @param file the location of the file to extract.
     */
    private void unzip(String file) {
        final File fSourceZip = new File(file);
        try {
            final String zipPath = file.substring(0, file.length() - 4);
            ZipFile zipFile = new ZipFile(fSourceZip);
            Enumeration<? extends ZipEntry> e = zipFile.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                File destinationFilePath = new File(zipPath, entry.getName());
                this.fileIOOrError(destinationFilePath.getParentFile(), destinationFilePath.getParentFile().mkdirs(), true);
                if (!entry.isDirectory()) {
                    final BufferedInputStream bis = new BufferedInputStream(zipFile.getInputStream(entry));
                    int b;
                    final byte[] buffer = new byte[GitHubUpdater.BYTE_SIZE];
                    final FileOutputStream fos = new FileOutputStream(destinationFilePath);
                    final BufferedOutputStream bos = new BufferedOutputStream(fos, GitHubUpdater.BYTE_SIZE);
                    while ((b = bis.read(buffer, 0, GitHubUpdater.BYTE_SIZE)) != -1) {
                        bos.write(buffer, 0, b);
                    }
                    bos.flush();
                    bos.close();
                    bis.close();
                    final String name = destinationFilePath.getName();
                    if (name.endsWith(".jar") && this.pluginExists(name)) {
                        File output = new File(this.updateFolder, name);
                        this.fileIOOrError(output, destinationFilePath.renameTo(output), true);
                    }
                }
            }
            zipFile.close();

            // Move any plugin data folders that were included to the right place, Bukkit won't do this for us.
            moveNewZipFiles(zipPath);

        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.SEVERE, "The auto-updater tried to unzip a new update file, but was unsuccessful.", e);
            this.result = GitHubUpdater.UpdateResult.FAIL_DOWNLOAD;
        } finally {
            this.fileIOOrError(fSourceZip, fSourceZip.delete(), false);
        }
    }

    /**
     * Find any new files extracted from an update into the plugin's data directory.
     * @param zipPath path of extracted files.
     */
    private void moveNewZipFiles(String zipPath) {
        File[] list = listFilesOrError(new File(zipPath));
        for (final File dFile : list) {
            if (dFile.isDirectory() && this.pluginExists(dFile.getName())) {
                // Current dir
                final File oFile = new File(this.plugin.getDataFolder().getParent(), dFile.getName());
                // List of existing files in the new dir
                final File[] dList = listFilesOrError(dFile);
                // List of existing files in the current dir
                final File[] oList = listFilesOrError(oFile);
                for (File cFile : dList) {
                    // Loop through all the files in the new dir
                    boolean found = false;
                    for (final File xFile : oList) {
                        // Loop through all the contents in the current dir to see if it exists
                        if (xFile.getName().equals(cFile.getName())) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        // Move the new file into the current dir
                        File output = new File(oFile, cFile.getName());
                        this.fileIOOrError(output, cFile.renameTo(output), true);
                    } else {
                        // This file already exists, so we don't need it anymore.
                        this.fileIOOrError(cFile, cFile.delete(), false);
                    }
                }
            }
            this.fileIOOrError(dFile, dFile.delete(), false);
        }
        File zip = new File(zipPath);
        this.fileIOOrError(zip, zip.delete(), false);
    }

    /**
     * Check if the name of a jar is one of the plugins currently installed, used for extracting the correct files out of a zip.
     *
     * @param name a name to check for inside the plugins folder.
     * @return true if a file inside the plugins folder is named this.
     */
    private boolean pluginExists(String name) {
        File[] plugins = listFilesOrError(new File("plugins"));
        for (final File file : plugins) {
            if (file.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check to see if the program should continue by evaluating whether the plugin is already updated, or shouldn't be updated.
     *
     * @return true if the version was located and is not the same as the remote's newest.
     */
    private boolean versionCheck()
    {
        if (this.type != UpdateType.NO_VERSION_CHECK) {
            if (this.hasTag(versionLatest) || !this.shouldUpdate(current, version)) {
                // We already have the latest version, or this build is tagged for no-update
                this.result = GitHubUpdater.UpdateResult.NO_UPDATE;
                return false;
            } else if(!version.isSemver()) {
                // The file's name did not contain the string 'vVersion'
                final String authorInfo = this.plugin.getDescription().getAuthors().isEmpty() ? "" : " (" + this.plugin.getDescription().getAuthors().get(0) + ")";
                this.plugin.getLogger().warning("The author of this plugin" + authorInfo + " has misconfigured their Auto Update system");
                this.plugin.getLogger().warning("File versions should follow the format 'vVERSION' as define by semver definition");
                this.plugin.getLogger().warning("Please notify the author of this error.");
                this.result = GitHubUpdater.UpdateResult.FAIL_NOVERSION;
                return false;
            }
        }
        return true;
    }

    /**
     * <b>If you wish to run mathematical versioning checks, edit this method.</b>
     * <p>
     * With default behavior, Updater will NOT verify that a remote version available on hexosse.github.com
     * which is not this version is indeed an "update".
     * If a version is present on hexosse.github.com that is not the version that is currently running,
     * Updater will assume that it is a newer version.
     * This is because there is no standard versioning scheme, and creating a calculation that can
     * determine whether a new update is actually an update is sometimes extremely complicated.
     * </p>
     * <p>
     * Updater will call this method from {@link #versionCheck()} before deciding whether
     * the remote version is actually an update.
     * If you have a specific versioning scheme with which a mathematical determination can
     * be reliably made to decide whether one version is higher than another, you may
     * revise this method, using the local and remote version parameters, to execute the
     * appropriate check.
     * </p>
     * <p>
     * Returning a value of <b>false</b> will tell the update process that this is NOT a new version.
     * Without revision, this method will always consider a remote version at all different from
     * that of the local version a new update.
     * </p>
     * @param localVersion the current version
     * @param remoteVersion the remote version
     * @return true if Updater should consider the remote version an update, false if not.
     */
    public boolean shouldUpdate(Version localVersion, Version remoteVersion) {
        return current.compare(version);
    }

    /**
     * Evaluate whether the version number is marked showing that it should not be updated by this program.
     *
     * @param version a version number to check for tags in.
     * @return true if updating should be disabled.
     */
    private boolean hasTag(String version) {
        for (final String string : GitHubUpdater.NO_UPDATE_TAG) {
            if (version.contains(string)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Make a connection to the hexosse.github.com API and request the newest file's details.
     *
     * @return true if successful.
     */
    private boolean read()
    {
        try {
            final URLConnection conn = this.url.openConnection();
            conn.setConnectTimeout(6000);

            conn.addRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.addRequestProperty("User-Agent", GitHubUpdater.USER_AGENT);
            conn.setDoOutput(true);

            final BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            final String response = reader.readLine();

            final JSONArray responseArray = (JSONArray) JSONValue.parse(response);

            if (responseArray.isEmpty()) {
                this.plugin.getLogger().warning("The updater could not find any files on repositry " + this.repository);
                this.result = UpdateResult.FAIL_BADID;
                return false;
            }

            JSONObject latestUpdate = (JSONObject) responseArray.get(0);
            this.versionLatest = (String) latestUpdate.get(GitHubUpdater.VERSION_VALUE);
            this.version = Version.parse(this.versionLatest);
            this.versionType = ( (Boolean)latestUpdate.get(GitHubUpdater.TYPE_DRAFT_VALUE)==true ? ReleaseType.DRAFT : ((Boolean)latestUpdate.get(GitHubUpdater.TYPE_PRERELEASE_VALUE)==true ? ReleaseType.PRERELEASE : (ReleaseType.RELEASE)) );

            final JSONArray assetsArray = (JSONArray) JSONValue.parse(latestUpdate.get(GitHubUpdater.ASSETS_VALUE).toString());

            if (assetsArray.isEmpty()) {
                this.plugin.getLogger().warning("The updater could not find any files on repositry " + this.repository);
                this.result = UpdateResult.FAIL_BADID;
                return false;
            }

            JSONObject latestAssetUpdate = (JSONObject) assetsArray.get(assetsArray.size() - 1);
            this.versionLink = (String)latestAssetUpdate.get(GitHubUpdater.LINK_VALUE);

            return true;
        } catch (final IOException e) {
            if (e.getMessage().contains("HTTP response code: 403")) {
                this.plugin.getLogger().severe("hexosse.github.com rejected the API key provided in plugins/Updater/config.yml");
                this.plugin.getLogger().severe("Please double-check your configuration to ensure it is correct.");
                this.result = UpdateResult.FAIL_API;
            } else {
                this.plugin.getLogger().severe("The updater could not contact hexosse.github.com for updating.");
                this.plugin.getLogger().severe("If you have not recently modified your configuration and this is the first time you are seeing this message, the site may be experiencing temporary downtime.");
                this.result = UpdateResult.FAIL_DBO;
            }
           return false;
        }
    }

    /**
     * Perform a file operation and log any errors if it fails.
     * @param file file operation is performed on.
     * @param result result of file operation.
     * @param create true if a file is being created, false if deleted.
     */
    private void fileIOOrError(File file, boolean result, boolean create) {
        if (!result) {
            this.plugin.getLogger().severe("The updater could not " + (create ? "create" : "delete") + " file at: " + file.getAbsolutePath());
        }
    }

    private File[] listFilesOrError(File folder) {
        File[] contents = folder.listFiles();
        if (contents == null) {
            this.plugin.getLogger().severe("The updater could not access files at: " + this.updateFolder.getAbsolutePath());
            return new File[0];
        } else {
            return contents;
        }
    }

    /**
     * Called on main thread when the Updater has finished working, regardless
     * of result.
     */
    public interface UpdateCallback {
        /**
         * Called when the updater has finished working.
         * @param updater The updater instance
         */
        void onFinish(GitHubUpdater updater);
    }

    private class UpdateRunnable implements Runnable {
        @Override
        public void run() {
            runUpdater();
        }
    }

    private void runUpdater()
    {
        if (this.url != null && (this.read() && this.versionCheck()))
        {
            // Obtain the results of the project's file feed
            if ((this.versionLink != null) && (this.type != UpdateType.NO_DOWNLOAD)) {
                String name = this.versionLink; //this.file.getName();
                // If it's a zip file, it shouldn't be downloaded as the plugin's name
                if (this.versionLink.endsWith(".zip")) {
                    name = this.versionLink.substring(this.versionLink.lastIndexOf("/") + 1);
                }
                this.saveFile(name);
            } else {
                this.result = UpdateResult.UPDATE_AVAILABLE;
            }
        }

        if (this.callback != null)
        {
            new BukkitRunnable() {
                @Override
                public void run() {
                    runCallback();
                }
            }.runTask(this.plugin);
        }
    }

    private void runCallback() {
        this.callback.onFinish(this);
    }
}