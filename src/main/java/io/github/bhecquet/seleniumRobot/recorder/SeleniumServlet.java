package io.github.bhecquet.seleniumRobot.recorder;

import com.google.gson.Gson;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;


public class SeleniumServlet extends HttpServlet {

    private Project project;

    public SeleniumServlet(Project project) {
        this.project = project;
    }


    private void insertFrameContextLines(Editor editor, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        WriteCommandAction.runWriteCommandAction(project, () -> {
            var doc = editor.getDocument();
            int pos = editor.getCaretModel().getOffset();
            String block = String.join("\n", lines) + "\n";
            doc.insertString(pos, block);
            editor.getCaretModel().moveToOffset(pos + block.length());
        });
    }


    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {

        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor != null) {

            try {
                SeleniumAction action = new Gson().fromJson(request.getReader(), SeleniumAction.class);


                // 2) IFRAME CONTEXT
                FrameContextManager fcm = SeleniumRecorderState.getFrameContextManager(project);
                fcm.ensureFrameContext(action.getFramePath());
                insertFrameContextLines(editor, fcm.consumePendingLines());

//imports
                insertImports(editor, action);

//declaration elements
                insertElement(editor, action);


                insertElementAction(editor, action);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(200);
        try {
            response.getOutputStream().print("OK");
            response.getOutputStream().flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Insert element into class if it does not exist
     */
    private String insertElement(Editor editor, SeleniumAction seleniumAction) {

        String code = seleniumAction.getWebElementString();
        String content = editor.getDocument().getText();

        // do not recreate element if it already exists
        if (!content.contains(seleniumAction.getElementName())) {
            int firstElementPosition = Math.max(0, content.indexOf("{")) + 1;

            WriteCommandAction.runWriteCommandAction(project, () ->
                    editor.getDocument().insertString(firstElementPosition, code)
            );
        }
        return null;
    }

    private void insertElementAction(Editor editor, SeleniumAction seleniumAction) {

        // Ignorer le bruit sur les SELECT (click/change) : on ne garde que "select"
        String elementType = seleniumAction.getElementType();
        String cmd = seleniumAction.getCommand();

        boolean isSelect = "SelectElement".equals(elementType) || "ListSelect".equals(elementType);
        if (isSelect && ("click".equals(cmd) || "change".equals(cmd))) {
            return;
        }

        // Fusionner les frappes : type / keyup / keydown / sendKeys
        boolean isTypingCmd = "type".equals(cmd) || "sendKeys".equals(cmd) || "keyup".equals(cmd) || "keydown".equals(cmd);

        // Ignore les sendKeys vides (souvent produits lors des pauses / espace / composition)
        if (isTypingCmd && (seleniumAction.getValue() == null || seleniumAction.getValue().isEmpty())) {
            return;
        }

        String code = seleniumAction.getFormattedCommand();
        String elementName = seleniumAction.getElementName();

        WriteCommandAction.runWriteCommandAction(project, () -> {
            int caretOffset = editor.getCaretModel().getCurrentCaret().getOffset();
            var doc = editor.getDocument();
            String text = doc.getText();

            // Dédupliquer clicks consécutifs sur le même élément
            if ("click".equals(cmd)) {
                String prevLine = previousNonEmptyLine(text, caretOffset);
                if (prevLine != null && prevLine.contains(elementName + ".click(")) {
                    return;
                }
            }

            // Si c'est une frappe clavier : remplacer la DERNIÈRE ligne sendKeys/setText du même élément
            if (isTypingCmd) {
                int[] lastCallRange = findLastTypingCallRange(text, caretOffset, elementName);
                if (lastCallRange != null) {
                    doc.replaceString(lastCallRange[0], lastCallRange[1], code);
                    editor.getCaretModel().getCurrentCaret().moveToOffset(lastCallRange[0] + code.length());
                    return;
                }
            }

            // 5) Sinon insertion normale
            doc.insertString(caretOffset, code);
            editor.getCaretModel().getCurrentCaret().moveToOffset(caretOffset + code.length());
        });
    }

    private String previousNonEmptyLine(String text, int caretOffset) {
        int i = Math.min(caretOffset - 1, text.length() - 1);
        while (i >= 0 && (text.charAt(i) == '\n' || text.charAt(i) == '\r')) i--;
        if (i < 0) return null;

        int lineStart = text.lastIndexOf('\n', i) + 1;
        int lineEnd = text.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = text.length();

        String line = text.substring(lineStart, lineEnd).trim();
        return line.isEmpty() ? null : line;
    }

    /**
     * Retourne {start, end} (end exclusif) de la dernière ligne contenant
     * elementName.sendKeys(...) ou elementName.setText(...) avant caretOffset.
     */
    private int[] findLastTypingCallRange(String text, int caretOffset, String elementName) {
        String pattern1 = elementName + ".sendKeys(";
        String pattern2 = elementName + ".setText(";

        int start = Math.min(caretOffset, text.length());
        int i1 = text.lastIndexOf(pattern1, start);
        int i2 = text.lastIndexOf(pattern2, start);
        int idx = Math.max(i1, i2);

        if (idx < 0) return null;

        int lineStart = text.lastIndexOf('\n', idx) + 1;
        int lineEnd = text.indexOf('\n', idx);
        if (lineEnd < 0) lineEnd = text.length();

        // inclure le \n si possible, pour remplacer proprement toute la ligne
        int end = (lineEnd + 1 <= text.length()) ? lineEnd + 1 : lineEnd;
        return new int[]{lineStart, end};
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(HttpServletResponse.SC_OK);
    }


    /**
     * Insère les imports nécessaires (By, Keys, HtmlElement, etc.) en tête du fichier.
     */
    private void insertImports(Editor editor, SeleniumAction seleniumAction) {

        WriteCommandAction.runWriteCommandAction(project, () -> {
            var doc = editor.getDocument();
            String content = doc.getText();

            // 1) ✅ Normalise : "package ...;import ..." => "package ...;\nimport ..."
            content = content.replaceFirst("(?m)^(\\s*package\\s+[^;]+;)(?=\\s*import\\s)", "$1\n");

            // 2) ✅ Déplace les imports placés AVANT le package (restes d'une exécution précédente)
            java.util.regex.Pattern pkgPat = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+[^;]+;");
            java.util.regex.Matcher pkgM = pkgPat.matcher(content);

            if (pkgM.find()) {
                int pkgStart = pkgM.start();
                if (pkgStart > 0) {
                    String beforePkg = content.substring(0, pkgStart);

                    java.util.regex.Pattern impPat = java.util.regex.Pattern.compile("(?m)^\\s*import\\s+[^;]+;\\s*$");
                    java.util.regex.Matcher impM = impPat.matcher(beforePkg);

                    java.util.List<String> preImports = new java.util.ArrayList<>();
                    while (impM.find()) {
                        preImports.add(impM.group().trim());
                    }

                    if (!preImports.isEmpty()) {
                        // retire ces imports du bloc avant package
                        String beforeClean = beforePkg.replaceAll("(?m)^\\s*import\\s+[^;]+;\\s*\\R?", "");
                        content = beforeClean + content.substring(pkgStart);

                        // recalcul end du package après nettoyage
                        pkgM = pkgPat.matcher(content);
                        pkgM.find();
                        int pkgEnd = pkgM.end();


                        String movedBlock = "\n" + String.join("\n", preImports) + "\n";
                        content = content.substring(0, pkgEnd) + movedBlock + content.substring(pkgEnd);
                    }
                }
            }

            // applique la normalisation
            doc.setText(content);

            // 3) ✅ Calcule où insérer : après le dernier import existant (sinon après package)
            String updated = doc.getText();
            java.util.regex.Matcher pkg2 = pkgPat.matcher(updated);
            int basePos = 0;
            if (pkg2.find()) basePos = pkg2.end();

            java.util.regex.Pattern impPat2 = java.util.regex.Pattern.compile("(?m)^\\s*import\\s+[^;]+;\\s*$");
            java.util.regex.Matcher allImp = impPat2.matcher(updated);

            int lastImportEnd = -1;
            while (allImp.find()) {
                if (allImp.start() >= basePos) lastImportEnd = allImp.end();
            }
            int insertPos = (lastImportEnd != -1) ? lastImportEnd : basePos;

            // 4) ✅ Ajoute uniquement les imports manquants
            java.util.List<SeleniumAction> onlyThisAction = java.util.Collections.singletonList(seleniumAction);
            java.util.Set<String> requiredImports = ImportBuilder.computeImports(onlyThisAction);

            java.util.List<String> missingImports = new java.util.ArrayList<>();
            for (String imp : requiredImports) {
                if (!updated.contains(imp)) {
                    missingImports.add(imp);
                }
            }
            if (missingImports.isEmpty()) {
                return;
            }

            String block = "\n" + String.join("\n", missingImports) + "\n";
            doc.insertString(insertPos, block + "\n");
        });
    }
}
