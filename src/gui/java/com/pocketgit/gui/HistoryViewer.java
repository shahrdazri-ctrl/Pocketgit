package com.pocketgit.gui;

import com.pocketgit.services.HistoryViewService;
import com.pocketgit.services.HistoryViewService.History;
import com.pocketgit.services.HistoryViewService.Node;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/** A read-only repository browser. All object reads run on one background worker. */
final class HistoryViewer {
    private static final int DISPLAY_LIMIT = 2_000;
    private static final int GRAPH_LANE_LIMIT = 15;
    private static final double ROW_HEIGHT = 74;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm z").withZone(ZoneId.systemDefault());
    private static final List<Color> LANE_COLORS = List.of(Color.web("#82d6aa"), Color.web("#86b8fb"), Color.web("#c7a4fa"), Color.web("#f5bd7e"));
    private record Edge(int fromRow, int toRow, int fromLane, int toLane) {}
    private final Path repository;
    private final Stage stage = new Stage();
    private final HistoryViewService service = new HistoryViewService();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "pocketgit-history-reader");
        thread.setDaemon(true);
        return thread;
    });
    private final Consumer<Throwable> failure;
    private final boolean smoke = Boolean.getBoolean("pocketgit.gui.smoke");
    private final Label branch = label("Loading…", "branch-pill");
    private final Label count = label("READING REPOSITORY", "eyebrow");
    private final Label status = label("Loading immutable history…", "muted");
    private final Button refresh = new Button("Refresh");
    private final ListView<HistoryViewService.Branch> branchList = new ListView<>();
    private final ListView<Node> historyList = new ListView<>();
    private final VBox details = new VBox(14);
    private final Label selectedHash = label("", "mono");
    private final Label selectedMessage = label("", "commit-message");
    private History history;
    private List<Edge> edges = List.of();
    private long selectionRequest;
    private String smokeSelection;

    HistoryViewer(Path repository, Runnable closed, Consumer<Throwable> failure) {
        this.repository = repository;
        this.failure = failure;
        stage.setOnHidden(event -> { worker.shutdownNow(); closed.run(); });
    }

    void show() {
        var root = new BorderPane();
        var logo = label("PG", "logo");
        var title = new VBox(2, label("PocketGit", "app-title"), label("Repository history", "muted"));
        var path = label(repository.toString(), "repository-path");
        path.setTooltip(new Tooltip(repository.toString()));
        var spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var header = new HBox(18, logo, title, branch, path, spacer, refresh);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");
        root.setTop(header);

        branchList.setCellFactory(ignored -> new BranchCell());
        branchList.setPlaceholder(label("No branches", "muted"));
        branchList.getSelectionModel().selectedItemProperty().addListener((ignored, old, selected) -> {
            if (selected == null || selected.hash() == null) return;
            for (int i = 0; i < historyList.getItems().size(); i++) {
                if (historyList.getItems().get(i).hash().equals(selected.hash())) {
                    historyList.getSelectionModel().select(i);
                    historyList.scrollTo(i);
                    return;
                }
            }
            status.setText("Branch tip lies beyond the " + DISPLAY_LIMIT + " displayed commits.");
        });
        var branchesPane = panel("BRANCHES", label("Click a branch to inspect its tip", "muted"), branchList);
        branchesPane.setMinWidth(175);
        historyList.setFixedCellSize(ROW_HEIGHT);
        historyList.setCellFactory(ignored -> new CommitCell());
        historyList.setPlaceholder(label("No commits yet. Make your first commit with the CLI.", "muted"));
        historyList.getSelectionModel().selectedItemProperty().addListener((ignored, old, node) -> { if (node != null) loadDetails(node); });
        var historyPane = panel("COMMIT GRAPH", count, historyList);
        historyPane.setMinWidth(320);
        details.setPadding(new Insets(20));
        details.getChildren().setAll(label("Select a commit", "commit-message"), label("Inspect its author, parents, and changed files.", "muted"));
        var detailsScroll = new ScrollPane(details);
        detailsScroll.setFitToWidth(true);
        var detailsPane = panel("COMMIT DETAILS", label("Changes compared with the first parent", "muted"), detailsScroll);
        detailsPane.setMinWidth(290);
        var split = new SplitPane(branchesPane, historyPane, detailsPane);
        split.setDividerPositions(0.18, 0.64);
        root.setCenter(split);

        var footer = new HBox(16, label("● READ ONLY", "read-only"), status);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("footer");
        root.setBottom(footer);
        var scene = new Scene(root, 1240, 780);
        scene.getStylesheets().add(styles());
        stage.setScene(scene);
        stage.setTitle("PocketGit · " + repository.getFileName());
        stage.setMinWidth(980);
        stage.setMinHeight(600);
        refresh.setOnAction(event -> loadHistory());
        stage.show();
        loadHistory();
    }

    private VBox panel(String title, javafx.scene.Node subtitle, javafx.scene.Node content) {
        var heading = new VBox(8, label(title, "eyebrow"), subtitle);
        heading.getStyleClass().add("panel-heading");
        var box = new VBox(heading, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    private void loadHistory() {
        refresh.setDisable(true);
        selectionRequest++;
        var task = new Task<History>() { @Override protected History call() throws IOException { return service.load(repository); } };
        task.setOnSucceeded(event -> {
            history = task.getValue();
            refresh.setDisable(false);
            branch.setText(history.currentBranch() == null ? "Detached HEAD" : history.currentBranch());
            branchList.getItems().setAll(history.branches());
            var displayed = history.commits().subList(0, Math.min(DISPLAY_LIMIT, history.commits().size()));
            edges = graphEdges(displayed);
            count.setText(history.commits().size() + " COMMITS · " + history.branches().size() + " BRANCHES");
            String displayStatus = history.commits().size() > DISPLAY_LIMIT ? "Showing the newest " + DISPLAY_LIMIT + " reachable commits." : "All branches loaded. Working files are untouched.";
            if (history.laneCount() > GRAPH_LANE_LIMIT) displayStatus += " Graph condenses lanes after " + GRAPH_LANE_LIMIT + ".";
            status.setText(displayStatus);
            historyList.getItems().setAll(displayed);
            historyList.refresh();
            if (!displayed.isEmpty()) {
                int index = smoke && displayed.size() > 1 ? 1 : 0;
                if (smoke) smokeSelection = displayed.get(index).hash();
                historyList.getSelectionModel().select(index);
                // Refresh can preserve the selected item and omit a selection event.
                loadDetails(displayed.get(index));
            } else {
                details.getChildren().setAll(label("No commits yet", "commit-message"), label("Stage files and create a commit with the CLI.", "muted"));
                if (smoke) finishSmoke(null);
            }
        });
        task.setOnFailed(event -> report(task.getException()));
        worker.execute(task);
    }

    private void loadDetails(Node node) {
        long request = ++selectionRequest;
        details.getChildren().setAll(label("Reading commit…", "muted"));
        var task = new Task<HistoryViewService.Details>() {
            @Override protected HistoryViewService.Details call() throws IOException { return service.details(repository, node.hash()); }
        };
        task.setOnSucceeded(event -> {
            if (request != selectionRequest) return;
            var value = task.getValue();
            selectedHash.setText(value.hash());
            selectedHash.setWrapText(true);
            selectedMessage.setText(value.commit().message());
            selectedMessage.setWrapText(true);
            var author = label(value.commit().authorName() + " <" + value.commit().authorEmail() + ">", "body");
            author.setWrapText(true);
            details.getChildren().setAll(selectedMessage, label("COMMIT", "eyebrow"), selectedHash,
                    label("AUTHOR", "eyebrow"), author, label(DATE.format(value.commit().timestamp()), "muted"), label("PARENTS", "eyebrow"));
            if (value.commit().parentHashes().isEmpty()) details.getChildren().add(label("Root commit", "muted"));
            for (String parent : value.commit().parentHashes()) {
                var parentLabel = label(parent, "mono");
                parentLabel.setWrapText(true);
                details.getChildren().add(parentLabel);
            }
            details.getChildren().add(label("TREE", "eyebrow"));
            var tree = label(value.commit().treeHash(), "mono");
            tree.setWrapText(true);
            details.getChildren().add(tree);
            details.getChildren().add(label("CHANGED FILES · " + value.changedFiles().size(), "eyebrow"));
            if (value.changedFiles().isEmpty()) details.getChildren().add(label("No file changes", "muted"));
            var changes = new VBox(7);
            changes.setId("changed-files");
            for (var file : value.changedFiles()) {
                var kind = label(switch (file.kind()) { case ADDED -> "A"; case MODIFIED -> "M"; case DELETED -> "D"; }, "file-kind " + file.kind().name().toLowerCase(java.util.Locale.ROOT));
                var name = label(file.path(), "file-path");
                name.setWrapText(true);
                var row = new HBox(10, kind, name);
                row.setAlignment(Pos.TOP_LEFT);
                changes.getChildren().add(row);
            }
            details.getChildren().add(changes);
            if (smoke) finishSmoke(value);
        });
        task.setOnFailed(event -> { if (request == selectionRequest) report(task.getException()); });
        worker.execute(task);
    }

    private void report(Throwable error) {
        refresh.setDisable(false);
        status.setText("Cannot read repository: " + error.getMessage());
        details.getChildren().setAll(label("Repository could not be read", "commit-message"), label(error.getMessage(), "muted"));
        if (smoke) { failure.accept(error); stage.close(); }
    }

    private void finishSmoke(HistoryViewService.Details value) {
        Platform.runLater(() -> {
            try {
                if (history == null || branchList.getItems().size() != history.branches().size()) throw new IOException("branch scene was not populated");
                if (value != null) {
                    if (!smokeSelection.equals(historyList.getSelectionModel().getSelectedItem().hash()) || !value.hash().equals(selectedHash.getText()) || !value.commit().message().equals(selectedMessage.getText())) {
                        throw new IOException("commit selection did not populate metadata");
                    }
                    var files = (VBox) details.lookup("#changed-files");
                    if (files == null || files.getChildren().size() != value.changedFiles().size()) throw new IOException("changed files were not rendered");
                }
                String screenshot = System.getProperty("pocketgit.gui.screenshot");
                if (screenshot != null) {
                    stage.getScene().getRoot().applyCss();
                    stage.getScene().getRoot().layout();
                    var image = stage.getScene().snapshot(null);
                    if (!ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", Path.of(screenshot).toFile())) throw new IOException("PNG writer is unavailable");
                }
                System.out.println("GUI smoke OK: " + history.branches().size() + " branches, " + history.commits().size() + " commits; scene selection and details verified.");
            } catch (Exception error) { failure.accept(error); }
            stage.close();
        });
    }

    private List<Edge> graphEdges(List<Node> nodes) {
        var indexes = new HashMap<String, Integer>();
        for (int i = 0; i < nodes.size(); i++) indexes.put(nodes.get(i).hash(), i);
        var result = new ArrayList<Edge>();
        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            for (String parent : node.commit().parentHashes()) {
                Integer end = indexes.get(parent);
                result.add(new Edge(i, end == null ? nodes.size() : end, node.lane(), end == null ? node.lane() : nodes.get(end).lane()));
            }
        }
        return List.copyOf(result);
    }

    private final class BranchCell extends ListCell<HistoryViewService.Branch> {
        @Override protected void updateItem(HistoryViewService.Branch item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) { setGraphic(null); return; }
            var name = label((item.current() ? "●  " : "◇  ") + item.name(), item.current() ? "current-branch" : "body");
            name.setTooltip(new Tooltip(item.name() + (item.hash() == null ? " · unborn branch" : " · " + item.hash())));
            var description = label(item.hash() == null ? "No commits" : item.hash().substring(0, 8), "mono muted");
            setGraphic(new VBox(5, name, description));
        }
    }

    private final class CommitCell extends ListCell<Node> {
        CommitCell() { getStyleClass().add("commit-cell"); }
        @Override protected void updateItem(Node node, boolean empty) {
            super.updateItem(node, empty);
            setText(null);
            if (empty || node == null || history == null) { setGraphic(null); return; }
            var canvas = new Canvas(24 + Math.min(history.laneCount(), GRAPH_LANE_LIMIT + 1) * 20, ROW_HEIGHT);
            var graphics = canvas.getGraphicsContext2D();
            int row = getIndex();
            graphics.setLineWidth(2);
            for (var edge : edges) {
                if (row < edge.fromRow() || row > edge.toRow()) continue;
                // Overflow commits retain a node and their true lane label; omit ambiguous edges.
                if (edge.fromLane() >= GRAPH_LANE_LIMIT || edge.toLane() >= GRAPH_LANE_LIMIT) continue;
                double start = edge.fromRow() * ROW_HEIGHT + ROW_HEIGHT / 2;
                double end = edge.toRow() * ROW_HEIGHT + ROW_HEIGHT / 2;
                double turn = Math.min(start + ROW_HEIGHT, end);
                double top = row * ROW_HEIGHT;
                graphics.setStroke(LANE_COLORS.get(edge.fromLane() % LANE_COLORS.size()));
                // Bend during the first row, then carry the parent lane to its node.
                double a = Math.max(start, top), b = Math.min(turn, top + ROW_HEIGHT);
                if (b > a) {
                    double x1 = 14 + edge.fromLane() * 20 + (edge.toLane() - edge.fromLane()) * 20 * (a - start) / (turn - start);
                    double x2 = 14 + edge.fromLane() * 20 + (edge.toLane() - edge.fromLane()) * 20 * (b - start) / (turn - start);
                    graphics.strokeLine(x1, a - top, x2, b - top);
                }
                double verticalStart = Math.max(turn, top), verticalEnd = Math.min(end, top + ROW_HEIGHT);
                if (verticalEnd > verticalStart) graphics.strokeLine(14 + edge.toLane() * 20, verticalStart - top, 14 + edge.toLane() * 20, verticalEnd - top);
            }
            graphics.setFill(LANE_COLORS.get(node.lane() % LANE_COLORS.size()));
            graphics.fillOval(8 + Math.min(node.lane(), GRAPH_LANE_LIMIT) * 20, ROW_HEIGHT / 2 - 6, 12, 12);
            var badges = new FlowPane(5, 3);
            for (String name : node.labels()) badges.getChildren().add(label(name, "ref-badge"));
            var hash = label(node.hash().substring(0, 8) + (node.lane() >= GRAPH_LANE_LIMIT ? " · lane " + (node.lane() + 1) : ""), "mono hash");
            var top = new HBox(9, hash, badges);
            top.setAlignment(Pos.CENTER_LEFT);
            var message = label(node.commit().message().lines().findFirst().orElse(""), "history-message");
            var meta = label(node.commit().authorName() + " · " + DATE.format(node.commit().timestamp()), "muted");
            var text = new VBox(4, top, message, meta);
            var rowBox = new HBox(8, canvas, text);
            rowBox.setAlignment(Pos.CENTER_LEFT);
            setTooltip(node.lane() >= GRAPH_LANE_LIMIT ? new Tooltip("Lane " + (node.lane() + 1) + " is condensed; inspect parent IDs in commit details.") : null);
            setGraphic(rowBox);
        }
    }

    private static Label label(String text, String styles) {
        var label = new Label(text);
        label.getStyleClass().addAll(styles.split(" "));
        return label;
    }

    private String styles() {
        // An inline data URL keeps the optional artifact self-contained without resource plugins.
        String css = """
                .root { -fx-font-family: 'Inter', 'Segoe UI', sans-serif; -fx-font-size: 12px; -fx-background-color: #11161c; -fx-text-fill: #e3e9ef; }
                .label { -fx-text-fill: #dbe3eb; }
                .header { -fx-padding: 20 22; -fx-background-color: #151c23; -fx-border-color: transparent transparent #29323c transparent; }
                .logo { -fx-background-color: #82d6aa; -fx-text-fill: #11231b; -fx-font-weight: bold; -fx-font-size: 20; -fx-padding: 11; -fx-background-radius: 10; }
                .app-title { -fx-font-size: 19; -fx-font-weight: bold; }
                .muted { -fx-text-fill: #8d9aaa; -fx-font-size: 11; }
                .branch-pill, .ref-badge { -fx-background-color: #263c34; -fx-text-fill: #a1e7bf; -fx-padding: 5 10; -fx-background-radius: 5; }
                .ref-badge { -fx-font-size: 10; -fx-padding: 2 6; }
                .repository-path { -fx-text-fill: #9ba8b7; -fx-font-family: monospace; }
                .eyebrow { -fx-text-fill: #8f9dab; -fx-font-size: 10; -fx-font-weight: bold; }
                .panel-heading { -fx-padding: 22 18 17 18; -fx-background-color: #131a21; -fx-border-color: transparent transparent #27303a transparent; }
                .split-pane { -fx-padding: 0; -fx-background-color: #11161c; }
                .split-pane-divider { -fx-padding: 0 1; -fx-background-color: #27303a; }
                .list-view { -fx-background-color: #11161c; -fx-background-insets: 0; -fx-padding: 0; -fx-border-width: 0; }
                .list-cell { -fx-background-color: #11161c; -fx-text-fill: #dbe3eb; -fx-padding: 9 14; -fx-border-color: transparent transparent #19222c transparent; }
                .list-cell:filled:hover { -fx-background-color: #192430; }
                .list-cell:filled:selected { -fx-background-color: #213448; }
                .list-cell:empty { -fx-border-color: transparent; }
                .commit-cell { -fx-padding: 0 14; -fx-border-width: 0; }
                .current-branch { -fx-text-fill: #9ee6ba; -fx-font-weight: bold; }
                .history-message { -fx-font-size: 13; -fx-font-weight: bold; }
                .mono, .file-path { -fx-font-family: monospace; -fx-font-size: 11; }
                .hash { -fx-text-fill: #9ec4ef; }
                .commit-message { -fx-font-size: 20; -fx-font-weight: bold; -fx-text-fill: #f0f4f8; }
                .file-kind { -fx-font-family: monospace; -fx-font-weight: bold; -fx-padding: 2 5; -fx-background-radius: 3; }
                .added { -fx-background-color: #203a2c; -fx-text-fill: #98dfb3; }
                .modified { -fx-background-color: #3c3221; -fx-text-fill: #edc68c; }
                .deleted { -fx-background-color: #432a2f; -fx-text-fill: #e89aa4; }
                .scroll-pane, .scroll-pane > .viewport { -fx-background-color: #11161c; -fx-border-width: 0; }
                .scroll-bar { -fx-background-color: #11161c; }
                .scroll-bar .thumb { -fx-background-color: #34404e; -fx-background-radius: 5; }
                .scroll-bar .increment-button, .scroll-bar .decrement-button { -fx-padding: 2; -fx-background-color: transparent; }
                .button { -fx-background-color: #263441; -fx-text-fill: #dbe4ed; -fx-padding: 8 15; -fx-background-radius: 5; -fx-cursor: hand; }
                .button:hover { -fx-background-color: #344858; }
                .button:disabled { -fx-opacity: 0.5; }
                .footer { -fx-padding: 12 20; -fx-background-color: #151c23; -fx-border-color: #29323c transparent transparent transparent; }
                .read-only { -fx-text-fill: #8ed5a8; -fx-font-size: 10; -fx-font-weight: bold; }
                .tooltip { -fx-background-color: #24303d; -fx-text-fill: #dbe3eb; }
                """;
        return "data:text/css;base64," + java.util.Base64.getEncoder().encodeToString(css.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
