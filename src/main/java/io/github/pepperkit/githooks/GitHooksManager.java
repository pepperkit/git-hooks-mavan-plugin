/*
 * Copyright (C) 2021 PepperKit
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */
package io.github.pepperkit.githooks;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.apache.maven.plugin.Mojo;

/**
 * Manages all the work with git hooks.
 */
public class GitHooksManager {

    static final String PLUGIN_NAME = "git-hooks-maven-plugin";

    private static final Path GIT_PATH = Paths.get(".git");

    private static final Path GIT_HOOKS_PATH = Paths.get(".git", "hooks");

    private static final Path ARCHIVES_PATH = GIT_HOOKS_PATH.resolve("archived");

    private static final String SHEBANG = "#!/bin/sh";

    static final Set<String> GIT_HOOKS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "applypatch-msg",
            "commit-msg",
            "fsmonitor-watchman",
            "post-update",
            "pre-applypatch",
            "pre-commit",
            "pre-merge-commit",
            "pre-push",
            "pre-rebase",
            "pre-receive",
            "prepare-commit-msg",
            "push-to-checkout",
            "update"
    )));

    private static final Set<PosixFilePermission> HOOK_FILE_PERMISSIONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            )));

    private final Mojo mojo;

    /**
     * Creates GitHooksManager with the provided mojo. Mojo is used to obtain the correct logger.
     * @param mojo mojo, which initiated the work with GitHooksManager
     */
    public GitHooksManager(Mojo mojo) {
        this.mojo = mojo;
    }

    /**
     * Checks that provided hook names are valid git hook names.
     * @param hooks map of hookName -> hookValue
     * @throws IllegalStateException if one of the hook names is not a valid git hook name
     */
    void checkProvidedHookNamesCorrectness(Map<String, String> hooks) {
        for (Map.Entry<String, String> entry : hooks.entrySet()) {
            if (!GIT_HOOKS.contains(entry.getKey())) {
                throw new IllegalStateException(
                        "`" + entry.getKey() + "` is not a git hook. Available hooks are: " + GIT_HOOKS);
            }
        }
    }

    /**
     * Determines if current working directory is the root directory of a git repository.
     * @return true if it is a git repository, else false
     */
    public boolean isGitRepository() {
      return Files.exists(GIT_PATH);
    }

    /**
     * Checks that git hooks directory exists, and creates it if it doesn't.
     * @throws IllegalStateException if git repository was not initialized
     *                               or there's an error on creating git hooks directory
     */
    void checkGitHooksDirAndCreateIfMissing() {
        if (!Files.exists(GIT_HOOKS_PATH)) {
            try {
                Files.createDirectories(GIT_HOOKS_PATH);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create directory " + GIT_HOOKS_PATH, e);
            }
        }
    }

    /**
     * Returns the list of currently installed hooks.
     * @return the list of existing hook files
     */
    List<File> getExistingHookFiles() {
        return GIT_HOOKS.stream()
                .map(this::getHookPath)
                .filter(h -> Files.exists(Paths.get(h)))
                .map(File::new)
                .collect(Collectors.toList());
    }

    /**
     * Writes hook file with the specified name and value.
     * @param hookName hook's name
     * @param hookValue hook file's content to write
     * @throws IOException if an error occurs on trying to write the file
     */
    void createHook(String hookName, String hookValue) throws IOException {
        String hookPath = getHookPath(hookName);
        String fullHookValue = SHEBANG + "\n" + hookValue.replaceAll("[ ]{2,}", "");

        Optional<String> existingHookValue = readHook(hookName);
        if (existingHookValue.isPresent() && existingHookValue.get().equals(fullHookValue)) {
            mojo.getLog().info("The hook `" + hookName + "` has not changed, skipping");

        } else {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(hookPath))) {
                mojo.getLog().info("Writing `" + hookName + "` hook");
                writer.write(fullHookValue);

                Path hookFilePath = Paths.get(hookPath);
                if (hookFilePath.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    Set<PosixFilePermission> currentPermissions = Files.getPosixFilePermissions(hookFilePath);
                    if (!currentPermissions.containsAll(HOOK_FILE_PERMISSIONS)) {
                        Files.setPosixFilePermissions(hookFilePath, HOOK_FILE_PERMISSIONS);
                    }
                }
            }
        }
    }

    private String getHookPath(String hookName) {
        return GIT_HOOKS_PATH + "/" + hookName;
    }

    /**
     * Prints the contents of hook's file.
     * @param hookName hook's name
     * @return true if hook existed, false - otherwise
     * @throws IOException if file cannot be read
     */
    boolean printHook(String hookName) throws IOException {
        Optional<String> hookValue = readHook(hookName);
        hookValue.ifPresent(h -> mojo.getLog().info(
                "`" + hookName + "` -> The following commands will be invoked: \n" + h));
        return hookValue.isPresent();
    }

    Optional<String> readHook(String hookName) throws IOException {
        Path hookFilePath = Paths.get(getHookPath(hookName));
        if (!Files.exists(hookFilePath)) {
            return Optional.empty();
        }
        return Optional.of(new String(Files.readAllBytes(hookFilePath)));
    }

    /**
     * Executes the hook's file.
     * @param hookName hook's name
     * @return true if hook existed, false - otherwise
     * @throws IOException if file cannot be read or executed
     * @throws IllegalStateException if is launched at Windows OS, since it's not supported for hooks execution
     */
    boolean executeHook(String hookName) throws InterruptedException, IOException {
        if (!GIT_HOOKS_PATH.getFileSystem().supportedFileAttributeViews().contains("posix") ||
                System.getProperty("os.name").toLowerCase().contains("windows")) {
            throw new IllegalStateException("It seems you use Windows OS, test " +
                    "execution of hooks is unavailable on Windows OS.");
        }

        Optional<String> hook = readHook(hookName);
        if (hook.isPresent()) {
            mojo.getLog().info(">>>>> Executing hook `" + hookName + "` <<<<<");
            Process process = Runtime.getRuntime().exec("sh -c " + getHookPath(hookName));
            Executors.newSingleThreadExecutor().submit(() -> new BufferedReader(
                    new InputStreamReader(process.getInputStream())).lines().forEach(mojo.getLog()::info));

            int exitCode = process.waitFor();
            mojo.getLog().info("Exit code is " + exitCode);
            mojo.getLog().info(">>>>> The hook `" + hookName + "` was executed with the "
                    + (exitCode == 0 ? "SUCCESS" : "ERROR") + " result <<<<<");
        }
        return hook.isPresent();
    }
}
