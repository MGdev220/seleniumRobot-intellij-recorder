package io.github.bhecquet.seleniumRobot.recorder;

import java.util.ArrayList;
import java.util.List;

public class FrameContextManager {

    private final List<FrameInfo> currentPath = new ArrayList<>();
    private final List<String> generated = new ArrayList<>();

    public void ensureFrameContext(List<FrameInfo> desiredPath) {

        if (desiredPath == null) {
            desiredPath = List.of();
        }

        int common = 0;

        // Trouver le plus long préfixe commun
        while (common < currentPath.size() &&
                common < desiredPath.size() &&
                currentPath.get(common).equals(desiredPath.get(common))) {
            common++;
        }

        // Si on doit quitter totalement les frames
        if (common == 0 && !currentPath.isEmpty()) {
            generated.add("\t\tio.github.bhecquet.seleniumrobot.core.context.SeleniumRobotContext.getWebDriver().switchTo().defaultContent();");
            currentPath.clear();
        } else {
            // Remonter partiellement
            for (int i = currentPath.size() - 1; i >= common; i--) {
                generated.add("\t\tio.github.bhecquet.seleniumrobot.core.context.SeleniumRobotContext.getWebDriver().switchTo().parentFrame();");
                currentPath.remove(i);
            }
        }

        // Descendre dans les nouvelles frames
        for (int i = common; i < desiredPath.size(); i++) {
            FrameInfo fr = desiredPath.get(i);
            generated.add("\t\tnew HtmlElement(\"iframe\", " + fr.getSelector() + ").switchToFrame();");
            currentPath.add(fr);
        }
    }

    /**
     * Vide et retourne les lignes générées depuis le dernier call.
     */
    public List<String> flushGenerated() {
        List<String> copy = new ArrayList<>(generated);
        generated.clear();
        return copy;
    }

    /**
     * Alias pour flushGenerated (nom plus lisible dans le servlet)
     */
    public List<String> consumePendingLines() {
        return flushGenerated();
    }
}