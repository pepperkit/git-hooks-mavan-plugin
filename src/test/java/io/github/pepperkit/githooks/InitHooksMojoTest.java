/*
 * Copyright (C) 2021 PepperKit
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */
package io.github.pepperkit.githooks;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class InitHooksMojoTest {

    GitHooksManager gitHooksManagerMock;

    InitHooksMojo initHooksMojo;

    @BeforeEach
    void beforeEach() {
        gitHooksManagerMock = mock(GitHooksManager.class);
        initHooksMojo = new InitHooksMojo();
        initHooksMojo.gitHooksManager = gitHooksManagerMock;
        when(gitHooksManagerMock.isGitRepository()).thenReturn(true);
    }

    @Test
    void executesNothingIfNotAGitRepository() throws MojoExecutionException {
        when(gitHooksManagerMock.isGitRepository()).thenReturn(false);
        initHooksMojo.execute();
        verify(gitHooksManagerMock, times(0)).getExistingHookFiles();
    }

    @Test
    void executesNothingIfHooksAreNotProvided() throws MojoExecutionException, IOException {
        initHooksMojo.execute();
        verify(gitHooksManagerMock, times(0)).checkProvidedHookNamesCorrectness(any());
        verify(gitHooksManagerMock, times(0)).checkGitHooksDirAndCreateIfMissing();
        verify(gitHooksManagerMock, times(0)).createHook(any(), any());
    }

    @Test
    void createsCorrectHooks() throws MojoExecutionException, IOException {
        Map<String, String> hooks = new HashMap<>();
        hooks.put("pre-commit", "mvn -B checkstyle:checkstyle");
        hooks.put("pre-push", "mvn -B verify");
        initHooksMojo.hooks = hooks;

        initHooksMojo.execute();

        verify(gitHooksManagerMock, times(1)).checkProvidedHookNamesCorrectness(hooks);
        verify(gitHooksManagerMock, times(1)).checkGitHooksDirAndCreateIfMissing();
        verify(gitHooksManagerMock, times(1))
                .createHook("pre-commit", hooks.get("pre-commit"));
        verify(gitHooksManagerMock, times(1))
                .createHook("pre-push", hooks.get("pre-push"));
    }

    @Test
    void initThrowsMojoExecutionExceptionIfCreatingOfHookFails() throws MojoExecutionException, IOException {
        Map<String, String> hooks = new HashMap<>();
        hooks.put("pre-commit", "mvn -B checkstyle:checkstyle");
        hooks.put("pre-push", "mvn -B verify");
        initHooksMojo.hooks = hooks;

        doThrow(new IOException()).when(gitHooksManagerMock)
                .createHook("pre-push", hooks.get("pre-push"));

        MojoExecutionException excThrown = assertThrows(MojoExecutionException.class, initHooksMojo::execute);
        assertThat(excThrown.getMessage()).contains("pre-push");
    }

    @Test
    void setHooksToNullIfNullStringProvidedInConfiguration() {
        initHooksMojo.setHooks("null");

        assertNull(initHooksMojo.hooks);
    }
}
