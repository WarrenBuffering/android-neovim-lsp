package dev.codex.kotlinls.jetbrainsbridge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarterBase;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPackage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtCallableDeclaration;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtNamedDeclaration;
import kotlin.coroutines.Continuation;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class KotlinLsBridgeStarter extends ApplicationStarterBase {
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, Project> openProjects = new ConcurrentHashMap<>();

    public KotlinLsBridgeStarter() {
        super(1, 2);
    }

    @Override
    public @NotNull String getCommandName() {
        return "kotlinls-bridge";
    }

    @Override
    public @NotNull String getUsageMessage() {
        return "kotlinls-bridge <port>";
    }

    @Override
    public boolean isHeadless() {
        return true;
    }

    @Override
    public void premain(@NotNull List<String> args) {
        System.setProperty("idea.trust.all.projects", "true");
    }

    @Override
    protected Object executeCommand(
        @NotNull List<String> args,
        String currentDirectory,
        @NotNull Continuation<? super com.intellij.ide.CliResult> continuation
    ) {
        int port = Integer.parseInt(args.get(args.size() - 1));
        try (
            Socket socket = new Socket("127.0.0.1", port);
            BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8))
        ) {
            writeResponse(writer, new BridgeResponse(0L, true, "ready", List.of(), null));
            String line;
            while ((line = reader.readLine()) != null) {
                BridgeRequest request = mapper.readValue(line, BridgeRequest.class);
                BridgeResponse response;
                try {
                    if ("complete".equals(request.method())) {
                        response = handleCompletion(request);
                    } else if ("sync".equals(request.method())) {
                        response = handleSync(request);
                    } else if ("prime".equals(request.method())) {
                        response = handleSync(request);
                    } else if ("ping".equals(request.method())) {
                        response = new BridgeResponse(request.id(), true, "pong", List.of(), null);
                    } else {
                        response = new BridgeResponse(request.id(), false, null, List.of(), "Unknown method: " + request.method());
                    }
                } catch (Throwable t) {
                    response = new BridgeResponse(request.id(), false, null, List.of(), stackTrace(t));
                }
                writeResponse(writer, response);
            }
        } catch (Throwable ignored) {
        } finally {
            openProjects.values().forEach(project -> {
                if (project != null && !project.isDisposed()) {
                    ProjectUtil.closeAndDispose(project);
                }
            });
            Application application = ApplicationManager.getApplication();
            if (application != null) {
                application.invokeLater(application::exit, ModalityState.nonModal());
            }
        }
        return com.intellij.ide.CliResult.OK;
    }

    private BridgeResponse handleCompletion(BridgeRequest request) {
        CompletionPayload payload = request.payload();
        if (payload == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), "Missing completion payload");
        }
        Project project = openProject(payload.projectRoot());
        DocumentHandle handle = loadDocumentHandle(payload.filePath());
        if (handle == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), "Unable to open file: " + payload.filePath());
        }
        String currentText = payload.text() == null ? handle.document().getText() : payload.text();
        replaceDocumentText(project, handle.document(), currentText);
        DumbService.getInstance(project).waitForSmartMode();
        replaceDocumentText(project, handle.document(), currentText);
        List<BridgeCompletionItem> smartItems = collectCompletionItems(project, handle.virtualFile(), handle.document(), payload.offset(), CompletionType.SMART, payload.limit());
        replaceDocumentText(project, handle.document(), currentText);
        List<BridgeCompletionItem> basicItems = collectCompletionItems(project, handle.virtualFile(), handle.document(), payload.offset(), CompletionType.BASIC, payload.limit());
        List<BridgeCompletionItem> merged = mergeCompletions(smartItems, basicItems, payload.limit());
        return new BridgeResponse(request.id(), true, null, merged, null);
    }

    private BridgeResponse handleSync(BridgeRequest request) {
        CompletionPayload payload = request.payload();
        if (payload == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), "Missing sync payload");
        }
        Project project = openProject(payload.projectRoot());
        if (payload.filePath() == null || payload.filePath().isBlank()) {
            return new BridgeResponse(request.id(), true, "synced", List.of(), null);
        }
        DocumentHandle handle = loadDocumentHandle(payload.filePath());
        if (handle == null) {
            return new BridgeResponse(request.id(), false, null, List.of(), "Unable to open file: " + payload.filePath());
        }
        replaceDocumentText(project, handle.document(), payload.text() == null ? handle.document().getText() : payload.text());
        return new BridgeResponse(request.id(), true, "synced", List.of(), null);
    }

    private Project openProject(String projectRoot) {
        return openProjects.compute(projectRoot, (key, existing) -> {
            if (existing != null && !existing.isDisposed()) {
                return existing;
            }
            Path root = Path.of(projectRoot);
            TrustedProjects.setProjectTrusted(root, true);
            Project project = ProjectUtil.openOrImport(root, OpenProjectTask.build().withForceOpenInNewFrame(true));
            if (project == null) {
                throw new IllegalStateException("Unable to open project: " + projectRoot);
            }
            TrustedProjects.setProjectTrusted(project, true);
            hideProjectFrame(project);
            return project;
        });
    }

    private void hideProjectFrame(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            var frame = WindowManagerEx.getInstanceEx().getFrame(project);
            if (frame != null) {
                frame.setVisible(false);
            }
        }, ModalityState.nonModal());
    }

    private void replaceDocumentText(Project project, Document document, String text) {
        if (text.equals(document.getText())) {
            return;
        }
        ApplicationManager.getApplication().invokeAndWait(
            () -> WriteCommandAction.runWriteCommandAction(
                project,
                () -> {
                    document.setText(text);
                    com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document);
                }
            ),
            ModalityState.nonModal()
        );
    }

    private DocumentHandle loadDocumentHandle(String filePath) {
        VirtualFile virtualFile = ApplicationManager.getApplication().runReadAction(
            (Computable<VirtualFile>) () -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(filePath))
        );
        if (virtualFile == null) {
            return null;
        }
        Document document = ApplicationManager.getApplication().runReadAction(
            (Computable<Document>) () -> FileDocumentManager.getInstance().getDocument(virtualFile)
        );
        if (document == null) {
            return null;
        }
        return new DocumentHandle(virtualFile, document);
    }

    private List<BridgeCompletionItem> collectCompletionItems(
        Project project,
        VirtualFile virtualFile,
        Document document,
        int offset,
        CompletionType completionType,
        int limit
    ) {
        Application app = ApplicationManager.getApplication();
        Editor editor = app.runReadAction((Computable<Editor>) () -> EditorFactory.getInstance().createEditor(document, project, virtualFile, false));
        try {
            app.invokeAndWait(() -> {
                editor.getCaretModel().moveToOffset(Math.max(0, Math.min(offset, document.getTextLength())));
                LookupManager.hideActiveLookup(project);
            }, ModalityState.nonModal());
            app.invokeAndWait(() -> {
                CodeCompletionHandlerBase handler = CodeCompletionHandlerBase.createHandler(completionType, false, false, false);
                handler.invokeCompletion(project, editor, 1, false);
            }, ModalityState.nonModal());
            Lookup lookup = waitForLookup(editor);
            if (lookup == null) {
                return List.of();
            }
            List<BridgeCompletionItem> items = new ArrayList<>();
            for (LookupElement element : lookup.getItems()) {
                items.add(toBridgeItem(project, element, completionType == CompletionType.SMART));
                if (items.size() >= limit) {
                    break;
                }
            }
            app.invokeAndWait(() -> LookupManager.hideActiveLookup(project), ModalityState.nonModal());
            return items;
        } finally {
            app.invokeAndWait(() -> EditorFactory.getInstance().releaseEditor(editor), ModalityState.nonModal());
        }
    }

    private Lookup waitForLookup(Editor editor) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            Lookup[] holder = new Lookup[1];
            ApplicationManager.getApplication().invokeAndWait(
                () -> holder[0] = LookupManager.getActiveLookup(editor),
                ModalityState.nonModal()
            );
            Lookup lookup = holder[0];
            if (lookup != null && !lookup.getItems().isEmpty()) {
                return lookup;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private List<BridgeCompletionItem> mergeCompletions(
        List<BridgeCompletionItem> smartItems,
        List<BridgeCompletionItem> basicItems,
        int limit
    ) {
        Map<String, BridgeCompletionItem> merged = new LinkedHashMap<>();
        for (BridgeCompletionItem item : smartItems) {
            merged.put(keyOf(item), item);
            if (merged.size() >= limit) {
                return new ArrayList<>(merged.values());
            }
        }
        for (BridgeCompletionItem item : basicItems) {
            merged.putIfAbsent(keyOf(item), item);
            if (merged.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(merged.values());
    }

    private String keyOf(BridgeCompletionItem item) {
        return String.join(
            "::",
            item.lookupString(),
            item.fqName() == null ? "" : item.fqName(),
            item.kind() == null ? "" : item.kind()
        );
    }

    private BridgeCompletionItem toBridgeItem(Project project, LookupElement element, boolean smart) {
        LookupElementPresentation presentation = LookupElementPresentation.renderElement(element);
        PsiElement psi = element.getPsiElement();
        if (psi == null && element.getObject() instanceof PsiElement lookupPsi) {
            psi = lookupPsi;
        }
        CompletionMetadata metadata = extractMetadata(psi);
        String label = firstNonBlank(presentation.getItemText(), element.getLookupString());
        String detail = joinNonBlank(presentation.getTailText(), presentation.getTypeText());
        Set<String> allLookupStrings = new LinkedHashSet<>(element.getAllLookupStrings());
        allLookupStrings.add(element.getLookupString());
        return new BridgeCompletionItem(
            label,
            element.getLookupString(),
            new ArrayList<>(allLookupStrings),
            detail,
            metadata.kind(),
            metadata.fqName(),
            metadata.packageName(),
            metadata.importable(),
            metadata.receiverType(),
            smart
        );
    }

    private CompletionMetadata extractMetadata(PsiElement psi) {
        if (psi == null) {
            return new CompletionMetadata(null, null, null, false, null);
        }
        if (psi instanceof KtNamedDeclaration declaration) {
            String fqName = declaration.getFqName() != null ? declaration.getFqName().asString() : null;
            String packageName = declaration.getContainingKtFile().getPackageFqName().asString();
            boolean importable = declaration.getParent() instanceof KtFile && fqName != null;
            String receiverType = declaration instanceof KtCallableDeclaration callable && callable.getReceiverTypeReference() != null
                ? callable.getReceiverTypeReference().getText()
                : null;
            String kind = declarationKind(declaration);
            return new CompletionMetadata(kind, fqName, packageName, importable, receiverType);
        }
        if (psi instanceof PsiClass psiClass) {
            String fqName = psiClass.getQualifiedName();
            String packageName = fqName == null || !fqName.contains(".") ? "" : fqName.substring(0, fqName.lastIndexOf('.'));
            return new CompletionMetadata("class", fqName, packageName, fqName != null, null);
        }
        if (psi instanceof PsiMethod method) {
            PsiClass owner = method.getContainingClass();
            String ownerName = owner != null ? owner.getQualifiedName() : null;
            String packageName = owner != null && owner.getQualifiedName() != null && owner.getQualifiedName().contains(".")
                ? owner.getQualifiedName().substring(0, owner.getQualifiedName().lastIndexOf('.'))
                : "";
            String fqName = ownerName == null ? method.getName() : ownerName + "." + method.getName();
            return new CompletionMetadata("function", fqName, packageName, false, null);
        }
        if (psi instanceof PsiField field) {
            PsiClass owner = field.getContainingClass();
            String ownerName = owner != null ? owner.getQualifiedName() : null;
            String packageName = owner != null && owner.getQualifiedName() != null && owner.getQualifiedName().contains(".")
                ? owner.getQualifiedName().substring(0, owner.getQualifiedName().lastIndexOf('.'))
                : "";
            String fqName = ownerName == null ? field.getName() : ownerName + "." + field.getName();
            return new CompletionMetadata("property", fqName, packageName, false, null);
        }
        if (psi instanceof PsiPackage psiPackage) {
            return new CompletionMetadata("package", psiPackage.getQualifiedName(), psiPackage.getQualifiedName(), true, null);
        }
        return new CompletionMetadata(null, null, null, false, null);
    }

    private String declarationKind(KtNamedDeclaration declaration) {
        if (declaration instanceof KtClassLikeDeclaration) {
            if (declaration instanceof KtClass ktClass && ktClass.isInterface()) {
                return "interface";
            }
            return "class";
        }
        if (declaration instanceof KtCallableDeclaration callable) {
            return callable instanceof org.jetbrains.kotlin.psi.KtNamedFunction ? "function" : "property";
        }
        return null;
    }

    private String joinNonBlank(String tailText, String typeText) {
        String trimmedTail = tailText == null ? "" : tailText.trim();
        String trimmedType = typeText == null ? "" : typeText.trim();
        if (!trimmedTail.isEmpty() && !trimmedType.isEmpty()) {
            return trimmedTail + " : " + trimmedType;
        }
        return firstNonBlank(trimmedTail, trimmedType);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private void writeResponse(BufferedWriter writer, BridgeResponse response) throws java.io.IOException {
        writer.write(mapper.writeValueAsString(response));
        writer.write('\n');
        writer.flush();
    }

    private String stackTrace(Throwable throwable) {
        StringWriter buffer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
    }

    private record BridgeRequest(long id, String method, CompletionPayload payload) {}

    private record CompletionPayload(String projectRoot, String filePath, String text, int offset, int limit) {}

    private record BridgeResponse(long id, boolean success, String message, List<BridgeCompletionItem> items, String error) {}

    private record DocumentHandle(VirtualFile virtualFile, Document document) {}

    private record BridgeCompletionItem(
        String label,
        String lookupString,
        List<String> allLookupStrings,
        String detail,
        String kind,
        String fqName,
        String packageName,
        boolean importable,
        String receiverType,
        boolean smart
    ) {}

    private record CompletionMetadata(
        String kind,
        String fqName,
        String packageName,
        boolean importable,
        String receiverType
    ) {}
}
