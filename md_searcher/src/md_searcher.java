import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.table.DefaultTableCellRenderer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class md_searcher_refactored {
    private JFrame root;
    private JTextField folderField;
    private JTextField searchField;
    private Path configPath;
    private String lastFolder;
    private JTable resultTable;
    private DefaultTableModel tableModel;
    private String currentSearchText; // 当前搜索文本用于高亮显示
    private List<SearchResult> currentResults = new ArrayList<>(); // 保存当前搜索结果用于精确定位

    public md_searcher_refactored() {
        root = new JFrame("Markdown内容搜索器");
        root.setSize(700, 500);
        root.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        root.setLayout(new BoxLayout(root.getContentPane(), BoxLayout.Y_AXIS));

        // 配置文件路径
        configPath = Paths.get(System.getProperty("user.dir"), "config.json");
        lastFolder = loadLastFolder();

        createWidgets();
        // 设置任务栏图标
        try {
            ImageIcon icon = new ImageIcon("e:/bilibili/md_searcher_java/md_searcher/icon.png");
            root.setIconImage(icon.getImage());
        } catch (Exception e) {
            // 图标加载失败时不影响程序运行
        }
        root.setVisible(true);
    }

    private String loadLastFolder() {
        if (Files.exists(configPath)) {
            try (InputStream is = Files.newInputStream(configPath)) {
                ObjectMapper mapper = new ObjectMapper();
                Config config = mapper.readValue(is, Config.class);
                return config.getLastFolder();
            } catch (Exception e) {
                e.printStackTrace();
                return "";
            }
        }
        return "";
    }

    private void saveLastFolder(String folder) {
        try (OutputStream os = Files.newOutputStream(configPath)) {
            ObjectMapper mapper = new ObjectMapper();
            Config config = new Config(folder);
            mapper.writeValue(os, config);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(root, "保存配置失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void selectFolder() {
        JFileChooser chooser = new JFileChooser(lastFolder);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
            String folder = chooser.getSelectedFile().getAbsolutePath();
            folderField.setText(folder);
            saveLastFolder(folder);
        }
    }

    private void searchText() {
        String folder = folderField.getText().trim();
        String searchText = searchField.getText().trim();
        this.currentSearchText = searchText; // 保存当前搜索文本
        currentResults.clear(); // 清空之前的搜索结果

        if (folder.isEmpty() || searchText.isEmpty()) {
            JOptionPane.showMessageDialog(root, "请输入文件夹路径和搜索文本", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        searchMarkdownFiles(new File(folder), searchText, currentResults);
        
        // 按文件路径和行号排序结果
        currentResults.sort(Comparator.comparing(SearchResult::getFilePath).thenComparingInt(SearchResult::getLineNum));
        
        displayResults(currentResults);
    }

    private void displayResults(List<SearchResult> results) {
        tableModel.setRowCount(0); // 清空现有结果
        String previousFile = null;
        
        for (SearchResult result : results) {
            // 获取文件名而非完整路径
            String currentFile = new File(result.getFilePath()).getName();
            // 同一文件只显示一次文件路径
            Object filePath = (currentFile.equals(previousFile)) ? "" : currentFile;
            
            tableModel.addRow(new Object[]{
                filePath,
                result.getLineNum(),
                result.getLineContent()
            });
            
            previousFile = currentFile;
        }
    }

    private void searchMarkdownFiles(File directory, String searchText, List<SearchResult> results) {
        Path startPath = directory.toPath();
        try {
            Files.walkFileTree(startPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.getFileName().toString().toLowerCase().endsWith(".md")) {
                        searchInFile(file.toFile(), searchText, results);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    System.err.println("访问文件失败: " + file + ", 错误: " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            JOptionPane.showMessageDialog(root, "遍历目录失败: " + directory + ", 错误: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void searchInFile(File file, String searchText, List<SearchResult> results) {
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            int lineNum = 1;
            while ((line = reader.readLine()) != null) {
                // 使用正则表达式进行精确匹配，支持大小写不敏感
                Pattern pattern = Pattern.compile(Pattern.quote(searchText), Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    results.add(new SearchResult(file.getAbsolutePath(), lineNum, line.trim()));
                }
                lineNum++;
            }
        } catch (IOException e) {
            System.err.println("读取文件错误: " + file.getAbsolutePath() + ", 错误: " + e.getMessage());
        }
    }

    private void openInDefaultEditor(int selectedRow) {
        if (selectedRow < 0 || selectedRow >= currentResults.size()) {
            return;
        }

        SearchResult result = currentResults.get(selectedRow);
        String filePath = result.getFilePath();
        int lineNum = result.getLineNum();
        String searchText = currentSearchText;

        // 尝试使用命令行参数直接跳转到指定行
        if (tryOpenWithCommandLine(filePath, lineNum)) {
            return;
        }

        // 如果命令行方式失败，使用增强版剪贴板+搜索方式
        tryEnhancedSearchOpen(filePath, searchText, lineNum);
    }

    private boolean tryOpenWithCommandLine(String filePath, int lineNum) {
        // 支持的编辑器及其命令行参数格式
        String[][] editors = {
            {"Notepad++", "notepad++.exe", "-n%d %%s"},
            {"VS Code", "code.exe", "--goto %%s:%d"},
            {"Sublime Text", "sublime_text.exe", "%%s:%d"},
            {"Atom", "atom.exe", "%%s:%d"},
            {"Vim", "vim.exe", "+%d %%s"},
            {"Emacs", "emacs.exe", "+%d %%s"}
        };

        File file = new File(filePath);
        if (!file.exists()) {
            JOptionPane.showMessageDialog(root, "文件不存在: " + filePath, "错误", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        // 尝试查找系统中已安装的编辑器
        for (String[] editor : editors) {
            try {
                String editorName = editor[0];
                String executable = editor[1];
                String argsFormat = editor[2];

                // 检查编辑器是否在系统路径中
                if (isExecutableInPath(executable)) {
                    String args = String.format(argsFormat, lineNum, filePath);
                    ProcessBuilder pb = new ProcessBuilder(executable, args.split(" ")[0], args.split(" ")[1]);
                    pb.start();
                    return true;
                }
            } catch (Exception e) {
                // 忽略单个编辑器的启动错误，尝试下一个
                continue;
            }
        }

        return false;
    }

    private boolean isExecutableInPath(String executable) {
        String[] pathDirs = System.getenv("PATH").split(File.pathSeparator);
        for (String dir : pathDirs) {
            File file = new File(dir, executable);
            if (file.exists() && file.canExecute()) {
                return true;
            }
        }
        return false;
    }

    private void tryEnhancedSearchOpen(String filePath, String searchText, int lineNum) {
        try {
            File file = new File(filePath);
            Desktop.getDesktop().open(file);

            // 动态等待时间，根据文件大小调整
            long fileSize = file.length();
            int waitTime = fileSize < 1024 * 1024 ? 2000 : // <1MB (优化等待时间)
                          fileSize < 5 * 1024 * 1024 ? 2000 : // <5MB (优化等待时间)
                          3000; // >5MB (优化等待时间)

            Thread.sleep(waitTime);

            // 将搜索文本和行号信息复制到剪贴板
            StringSelection selection = new StringSelection(searchText);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);

            // 使用更可靠的方式发送快捷键
            Robot robot = new Robot();
            robot.setAutoDelay(30); // 设置每次操作后的延迟 (缩短自动延迟)

            // 打开搜索框 (大多数编辑器支持Ctrl+F)
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_F);
            robot.keyRelease(KeyEvent.VK_F);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            Thread.sleep(500); // 缩短等待时间，确保搜索框打开

            // 粘贴搜索文本
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            Thread.sleep(500); // 缩短等待时间，确保文本粘贴完成

            // 针对Typora优化：使用搜索后直接按Enter键确认
            robot.keyPress(KeyEvent.VK_ENTER);
            robot.keyRelease(KeyEvent.VK_ENTER);
            Thread.sleep(600); // 优化等待时间，确保Typora搜索完成

            // 对于Typora，额外增加多次F3查找以确保定位准确性
            int occurrencesBefore = countOccurrencesBeforeLine(filePath, searchText, lineNum);
            // 减少一次F3按键，避免多按问题
            for (int i = 0; i < Math.max(0, occurrencesBefore - 1); i++) {
                robot.keyPress(KeyEvent.VK_F3);
                robot.keyRelease(KeyEvent.VK_F3);
                Thread.sleep(20); // 缩短F3等待时间至20ms，满足100ms以内要求
            }

        } catch (Exception e) {
            JOptionPane.showMessageDialog(root, "无法精确定位到搜索文本: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private int getCharPositionInLine(String filePath, int lineNum, String searchText) {
        // 获取搜索文本在目标行的起始字符位置
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(filePath), StandardCharsets.UTF_8)) {
            String line;
            int currentLine = 1;
            Pattern pattern = Pattern.compile(Pattern.quote(searchText), Pattern.CASE_INSENSITIVE);

            while ((line = reader.readLine()) != null && currentLine <= lineNum) {
                if (currentLine == lineNum) {
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        return matcher.start();
                    }
                    break;
                }
                currentLine++;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private int countOccurrencesBeforeLine(String filePath, String searchText, int targetLine) {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(filePath), StandardCharsets.UTF_8)) {
            String line;
            int lineNum = 1;
            Pattern pattern = Pattern.compile(Pattern.quote(searchText), Pattern.CASE_INSENSITIVE);

            while ((line = reader.readLine()) != null && lineNum < targetLine) {
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()) {
                    count++;
                }
                lineNum++;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return count;
    }

    private void createWidgets() {
        // 文件夹选择区域
        JPanel folderPanel = new JPanel(new BorderLayout());
        folderPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));
        folderPanel.add(new JLabel("文件夹路径:"), BorderLayout.WEST);

        folderField = new JTextField(lastFolder);
        folderPanel.add(folderField, BorderLayout.CENTER);

        JButton selectBtn = new JButton("浏览");
        selectBtn.addActionListener(e -> selectFolder());
        folderPanel.add(selectBtn, BorderLayout.EAST);

        // 搜索区域
        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));
        searchPanel.add(new JLabel("搜索文本:"), BorderLayout.WEST);

        searchField = new JTextField();
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    searchText();
                }
            }
        });
        searchPanel.add(searchField, BorderLayout.CENTER);

        JButton searchBtn = new JButton("搜索");
        searchBtn.addActionListener(e -> searchText());
        searchPanel.add(searchBtn, BorderLayout.EAST);

        root.add(folderPanel);
        root.add(searchPanel);

        // 结果显示区域
        JPanel resultPanel = new JPanel(new BorderLayout());
        resultPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));
        resultPanel.add(new JLabel("搜索结果:"), BorderLayout.NORTH);

        tableModel = new DefaultTableModel(new String[]{"文件路径", "行号", "内容"}, 0);
        resultTable = new JTable(tableModel) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        // 设置自定义单元格渲染器用于高亮搜索文本
        resultTable.getColumnModel().getColumn(2).setCellRenderer(new HighlightedTextRenderer());
        resultTable.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = resultTable.getSelectedRow();
                    openInDefaultEditor(row);
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(resultTable);
        resultPanel.add(scrollPane, BorderLayout.CENTER);

        root.add(resultPanel);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(md_searcher_refactored::new);
    }

    // 内部类用于配置文件
    private static class Config {
        private String lastFolder;

        public Config() {}

        public Config(String lastFolder) {
            this.lastFolder = lastFolder;
        }

        public String getLastFolder() {
            return lastFolder;
        }

        public void setlastFolder(String lastFolder) {
            this.lastFolder = lastFolder;
        }
    }

    // 自定义单元格渲染器，用于高亮显示搜索文本
    private class HighlightedTextRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, 
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if (value != null && currentSearchText != null && !currentSearchText.isEmpty()) {
                String text = value.toString();
                String search = currentSearchText;
                Pattern pattern = Pattern.compile(Pattern.quote(search), Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(text);

                if (matcher.find()) {
                    int index = matcher.start();
                    // 使用HTML格式化高亮显示搜索文本
                    String highlightedText = text.substring(0, index) +
                        "<span style=\"background-color: yellow;\">" +
                        text.substring(index, index + search.length()) +
                        "</span>" +
                        text.substring(index + search.length());
                    setText("<html>" + highlightedText + "</html>");
                }
            }
            return this;
        }
    }

    // 内部类用于搜索结果
    private static class SearchResult {
        private String filePath;
        private int lineNum;
        private String lineContent;

        public SearchResult(String filePath, int lineNum, String lineContent) {
            this.filePath = filePath;
            this.lineNum = lineNum;
            this.lineContent = lineContent;
        }

        public String getFilePath() {
            return filePath;
        }

        public int getLineNum() {
            return lineNum;
        }

        public String getLineContent() {
            return lineContent;
        }
    }
}