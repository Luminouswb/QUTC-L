package com.qutc.launcher.ui.pages;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.core.diagnose.CrashAnalyzer;
import com.qutc.launcher.ui.components.Dialogs;
import com.qutc.launcher.ui.theme.Ui;
import com.qutc.launcher.utils.Log;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 阶段 10：日志查看 + 崩溃诊断。
 *
 * <p>左侧列出启动器日志与各版本的游戏日志（含 crash-reports），右侧显示内容，
 * 自动跑 {@link CrashAnalyzer} 给出结论与处理建议。支持复制与导出。
 */
public final class LogsPage implements Page {

    private static final int MAX_TAIL_BYTES = 768 * 1024;

    private record LogFile(String label, Path path) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final VBox root = new VBox(14);
    private final ComboBox<String> sourceBox = new ComboBox<>();
    private final ListView<LogFile> fileList = new ListView<>();
    private final TextArea contentArea = new TextArea();
    private final VBox diagnosisBox = new VBox(8);
    private final Label statusLabel = new Label();

    private LogFile current;

    public LogsPage() {
        root.setPadding(new Insets(4, 6, 8, 0));

        Label title = new Label("日志与崩溃诊断");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("游戏日志按版本存放；崩溃会自动匹配常见原因并给出处理建议");
        subtitle.getStyleClass().add("page-subtitle");

        sourceBox.setPrefWidth(280);
        sourceBox.setItems(FXCollections.observableArrayList("启动器日志", "游戏日志（全部版本）"));
        sourceBox.setValue("游戏日志（全部版本）");
        sourceBox.valueProperty().addListener((obs, old, value) -> reloadList());

        Button refresh = Ui.animate(ghost("刷新"));
        refresh.setOnAction(event -> reloadList());
        Button copy = Ui.animate(ghost("复制"));
        copy.setOnAction(event -> copyContent());
        Button export = Ui.animate(ghost("导出…"));
        export.setOnAction(event -> exportContent());

        HBox toolbar = new HBox(10, sourceBox, refresh, copy, export);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        fileList.setPrefWidth(330);
        fileList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(LogFile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item.label());
                getStyleClass().removeAll("log-item-crash");
                if (looksLikeCrashFile(item.path())) {
                    getStyleClass().add("log-item-crash");
                }
            }
        });
        fileList.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            if (value != null) {
                showFile(value);
            }
        });

        contentArea.setEditable(false);
        contentArea.setWrapText(false);
        contentArea.setStyle("-fx-font-family: 'Cascadia Mono', 'Consolas', monospace; -fx-font-size: 12px;");
        VBox.setVgrow(contentArea, Priority.ALWAYS);

        diagnosisBox.setPadding(new Insets(4, 0, 0, 0));

        statusLabel.getStyleClass().add("muted");

        VBox right = new VBox(8, contentArea, diagnosisBox, statusLabel);
        HBox.setHgrow(right, Priority.ALWAYS);
        VBox.setVgrow(right, Priority.ALWAYS);

        HBox body = new HBox(12, fileList, right);
        VBox.setVgrow(body, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        root.getChildren().addAll(new VBox(4, title, subtitle), toolbar, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        reloadList();
    }

    @Override
    public String id() {
        return "logs";
    }

    @Override
    public String title() {
        return "日志";
    }

    @Override
    public Node view() {
        return root;
    }

    @Override
    public void onShow() {
        reloadList();
    }

    // ---- 列表 ----

    private void reloadList() {
        List<LogFile> files = new ArrayList<>();
        boolean launcher = "启动器日志".equals(sourceBox.getValue());
        try {
            if (launcher) {
                files.addAll(collect(AppPaths.logsDir(), "launcher-", "启动器"));
            } else {
                try (Stream<Path> versions = Files.list(AppPaths.versionsDir())) {
                    versions.filter(Files::isDirectory).forEach(versionDir -> {
                        String versionId = versionDir.getFileName().toString();
                        files.addAll(collect(AppPaths.versionLogsDir(versionId), "qutc-game-", versionId));
                        // 游戏自己写的 latest.log（分离启动时更可靠）
                        Path latest = AppPaths.gameDir(versionId, true).resolve("logs").resolve("latest.log");
                        if (!Files.isRegularFile(latest)) {
                            latest = versionDir.resolve("logs").resolve("latest.log");
                        }
                        if (Files.isRegularFile(latest)) {
                            files.add(new LogFile(versionId + " · latest.log（游戏自带）", latest));
                        }
                        Path crashReports = versionDir.resolve("crash-reports");
                        files.addAll(collectCrashReports(crashReports, versionId));
                    });
                }
            }
        } catch (IOException error) {
            Log.warn("Logs", "扫描日志失败: " + error.getMessage());
        }
        files.sort(Comparator.comparing((LogFile file) -> lastModified(file.path())).reversed());
        fileList.setItems(FXCollections.observableArrayList(files));
        statusLabel.setText("共 " + files.size() + " 个日志文件" + (files.isEmpty() ? "（启动一次游戏后再来看）" : ""));
        if (!files.isEmpty() && fileList.getSelectionModel().getSelectedItem() == null) {
            fileList.getSelectionModel().select(0);
        }
    }

    private List<LogFile> collect(Path dir, String prefix, String versionLabel) {
        List<LogFile> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .forEach(path -> files.add(new LogFile(versionLabel + " · " + path.getFileName(), path)));
        } catch (IOException ignored) {
            // 目录不可读时返回空列表即可
        }
        return files;
    }

    private List<LogFile> collectCrashReports(Path dir, String versionLabel) {
        List<LogFile> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .forEach(path -> files.add(new LogFile(versionLabel + " · crash/" + path.getFileName(), path)));
        } catch (IOException ignored) {
            // 忽略
        }
        return files;
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException error) {
            return Instant.EPOCH;
        }
    }

    private boolean looksLikeCrashFile(Path path) {
        String name = path.getFileName().toString();
        return name.contains("crash") || name.contains("hs_err");
    }

    // ---- 内容 ----

    private void showFile(LogFile file) {
        current = file;
        String text;
        try {
            text = readTail(file.path(), MAX_TAIL_BYTES);
        } catch (IOException error) {
            text = "读取失败：" + error.getMessage();
        }
        contentArea.setText(text);
        contentArea.positionCaret(text.length());
        renderDiagnosis(text);
        statusLabel.setText(file.path().toString() + " · " + text.length() + " 字符");
    }

    private void renderDiagnosis(String text) {
        diagnosisBox.getChildren().clear();
        List<CrashAnalyzer.Diagnosis> diagnoses = CrashAnalyzer.analyze(text);
        if (diagnoses.isEmpty()) {
            Label ok = new Label(CrashAnalyzer.looksLikeCrash(text)
                    ? "⚠ 检测到崩溃，但没有命中已知原因；请把日志导出后反馈给开发者"
                    : "✓ 没有检测到崩溃特征");
            ok.getStyleClass().add("muted");
            diagnosisBox.getChildren().add(ok);
            return;
        }
        for (CrashAnalyzer.Diagnosis diagnosis : diagnoses) {
            VBox card = new VBox(4);
            card.getStyleClass().add("card");
            card.setPadding(new Insets(10, 12, 10, 12));
            Label header = new Label(switch (diagnosis.severity()) {
                case FATAL -> "✕ " + diagnosis.title();
                case ERROR -> "⚠ " + diagnosis.title();
                case WARN -> "! " + diagnosis.title();
            });
            header.setStyle("-fx-font-weight: bold; -fx-text-fill: "
                    + (diagnosis.severity() == CrashAnalyzer.Severity.WARN ? "#e8c46a" : "#ff7a7a") + ";");
            Label suggestion = new Label("建议：" + diagnosis.suggestion());
            suggestion.setWrapText(true);
            Label detail = new Label(diagnosis.detail().strip());
            detail.setWrapText(true);
            detail.setStyle("-fx-font-family: 'Cascadia Mono', 'Consolas', monospace; -fx-text-fill: #8d95ab;");
            card.getChildren().addAll(header, suggestion, detail);
            diagnosisBox.getChildren().add(card);
        }
    }

    private String readTail(Path path, int maxBytes) throws IOException {
        long size = Files.size(path);
        if (size <= maxBytes) {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(path)) {
            channel.position(size - maxBytes);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(maxBytes);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // 读满 maxBytes 或到文件尾
            }
            String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            int firstNewline = text.indexOf('\n');
            return firstNewline > 0 ? text.substring(firstNewline + 1) : text;
        }
    }

    private void copyContent() {
        String text = contentArea.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("已复制 " + text.length() + " 字符到剪贴板");
    }

    private void exportContent() {
        if (current == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出日志");
        chooser.setInitialFileName(current.path().getFileName().toString());
        java.io.File target = chooser.showSaveDialog(root.getScene() == null ? null : root.getScene().getWindow());
        if (target == null) {
            return;
        }
        try {
            Files.writeString(target.toPath(), contentArea.getText(), StandardCharsets.UTF_8);
            Dialogs.info("导出日志", "已导出到 " + target, null);
        } catch (IOException error) {
            Dialogs.info("导出日志", "导出失败：" + error.getMessage(), null);
        }
    }

    private Button ghost(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("button-ghost");
        return button;
    }
}
