package io.github.bhecquet.seleniumRobot.recorder;

import com.intellij.openapi.project.Project;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SeleniumRecorderState {

    private static final Map<Project, FrameContextManager> managers = new ConcurrentHashMap<>();

    public static FrameContextManager getFrameContextManager(Project p) {
        return managers.computeIfAbsent(p, k -> new FrameContextManager());
    }
}