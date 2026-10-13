package discshelf.win;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The shelf: every disc found in the sources, as tiles. */
final class MainWindow extends JFrame {
    private static final Color[][] TILE_COLOURS = {
            {new Color(0x27364B), new Color(0x18212E)}, {new Color(0x3A2C4D), new Color(0x211A2D)},
            {new Color(0x214540), new Color(0x152B28)}, {new Color(0x4A3324), new Color(0x2B1F17)},
            {new Color(0x46283A), new Color(0x291923)}, {new Color(0x2D3F2A), new Color(0x1B2619)}};
    private static final Color ACCENT = new Color(0x4FA3FF);
    /** Poster-shaped tiles, so covers show whole. */
    private static final int TILE_W = 186, TILE_H = 286;

    private final Library library;
    private final DefaultListModel<Disc> model = new DefaultListModel<>();
    private final JList<Disc> grid = new JList<>(model);
    private final JLabel status = new JLabel(" ");
    private final JTextField search = new JTextField(18);
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JLabel emptyTitle = new JLabel("", SwingConstants.CENTER);
    private final JTextArea emptyDetail = new JTextArea();
    private List<Disc> all = new ArrayList<>();
    private SwingWorker<Library.Result, String> scanning;
    private final Art art;

    MainWindow(Library library) {
        super("Disc Shelf");
        this.library = library;
        this.art = new Art(library.dir(), grid::repaint);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 600));
        setSize(1300, 820);
        setLocationRelativeTo(null);

        add(topBar(), BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(statusBar(), BorderLayout.SOUTH);

        grid.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        grid.setVisibleRowCount(-1);
        grid.setFixedCellWidth(TILE_W);
        grid.setFixedCellHeight(TILE_H);
        grid.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        grid.setCellRenderer(new TileRenderer());
        javax.swing.ToolTipManager.sharedInstance().registerComponent(grid);
        grid.setBackground(getContentPane().getBackground());
        grid.setBorder(BorderFactory.createEmptyBorder(16, 28, 16, 28));
        grid.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) openSelected();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int i = grid.locationToIndex(e.getPoint());
                if (i < 0) return;
                grid.setSelectedIndex(i);
                discMenu(model.get(i)).show(grid, e.getX(), e.getY());
            }
        });
        bind(grid, "ENTER", this::openSelected);
        bind(grid, "CONTEXT_MENU", () -> {
            Disc d = grid.getSelectedValue();
            if (d != null) discMenu(d).show(grid, 40, 40);
        });

        JScrollPane scroll = new JScrollPane(grid);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        center.add(scroll, "grid");
        center.add(emptyPanel(), "empty");

        showDiscs(library.discs());
        rescan();
    }

    // ------------------------------------------------------------ layout

    private JComponent topBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(18, 28, 6, 28));
        JLabel title = new JLabel("Disc Shelf");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        bar.add(title, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        search.putClientProperty("JTextField.placeholderText", "Search");
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filter();
            }
        });
        buttons.add(search);
        buttons.add(button("Add folder…", this::addFolder));
        buttons.add(button("Sources", this::editSources));
        buttons.add(button("Rescan", this::rescan));
        buttons.add(button("Settings", this::settings));
        bar.add(buttons, BorderLayout.EAST);
        return bar;
    }

    private JComponent statusBar() {
        status.setBorder(BorderFactory.createEmptyBorder(6, 30, 10, 30));
        status.setForeground(Color.GRAY);
        return status;
    }

    private JComponent emptyPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(80, 120, 40, 120));
        emptyTitle.setFont(emptyTitle.getFont().deriveFont(Font.BOLD, 22f));
        emptyTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyDetail.setEditable(false);
        emptyDetail.setLineWrap(true);
        emptyDetail.setWrapStyleWord(true);
        emptyDetail.setOpaque(false);
        emptyDetail.setFont(emptyDetail.getFont().deriveFont(15f));
        emptyDetail.setMaximumSize(new Dimension(720, 400));
        emptyDetail.setAlignmentX(Component.CENTER_ALIGNMENT);
        JButton add = button("Add a folder or network share…", this::addFolder);
        add.setAlignmentX(Component.CENTER_ALIGNMENT);
        p.add(emptyTitle);
        p.add(Box.createVerticalStrut(14));
        p.add(emptyDetail);
        p.add(Box.createVerticalStrut(18));
        p.add(add);
        return p;
    }

    private static JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static void bind(JComponent c, String key, Runnable action) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key);
        c.getActionMap().put(key, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    // ------------------------------------------------------------ discs

    private void showDiscs(List<Disc> discs) {
        all = discs;
        filter();
    }

    private void filter() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        Disc selected = grid.getSelectedValue();
        model.clear();
        for (Disc d : all) if (q.isEmpty() || d.title.toLowerCase(Locale.ROOT).contains(q)) model.addElement(d);
        if (selected != null) grid.setSelectedValue(selected, true);
        if (grid.getSelectedIndex() < 0 && !model.isEmpty()) grid.setSelectedIndex(0);
        cards.show(center, model.isEmpty() && q.isEmpty() ? "empty" : "grid");
        if (model.isEmpty() && q.isEmpty()) explainEmpty(new ArrayList<>());
    }

    private void explainEmpty(List<String> notes) {
        if (library.sources().isEmpty()) {
            emptyTitle.setText("No discs on the shelf yet");
            emptyDetail.setText("Add the folders where your DVD and Blu-ray discs are kept: a drive, a USB disk, "
                    + "or a network share such as \\\\192.168.1.4\\Movies. Disc Shelf finds .iso files and discs "
                    + "copied as folders (VIDEO_TS or BDMV) in them and in the folders below.");
        } else if (scanning != null) {
            emptyTitle.setText("Looking for discs…");
            emptyDetail.setText("");
        } else {
            emptyTitle.setText("No discs found");
            StringBuilder sb = new StringBuilder();
            for (String n : notes) sb.append(n).append("\n");
            if (sb.length() == 0) sb.append("Your sources hold no .iso files or disc folders.\n");
            sb.append("\nCheck the folders under Sources, then press Rescan.");
            emptyDetail.setText(sb.toString());
        }
    }

    private void rescan() {
        if (scanning != null) return;
        art.retryMissing();
        status.setText("Looking for discs…");
        scanning = new SwingWorker<>() {
            @Override
            protected Library.Result doInBackground() {
                return library.scan(this::publish);
            }

            @Override
            protected void process(List<String> folders) {
                status.setText("Looking in " + folders.get(folders.size() - 1));
            }

            @Override
            protected void done() {
                scanning = null;
                try {
                    Library.Result r = get();
                    showDiscs(r.discs);
                    String count = r.discs.size() + (r.discs.size() == 1 ? " disc" : " discs");
                    status.setText(r.notes.isEmpty() ? count : count + "   ·   " + String.join("   ·   ", r.notes));
                    status.setToolTipText(r.notes.isEmpty() ? null : "<html>" + String.join("<br>", r.notes) + "</html>");
                    if (r.discs.isEmpty()) explainEmpty(r.notes);
                } catch (Exception e) {
                    status.setText("The scan stopped: " + e.getMessage());
                }
            }
        };
        if (all.isEmpty()) explainEmpty(new ArrayList<>());
        scanning.execute();
    }

    private void openSelected() {
        Disc d = grid.getSelectedValue();
        if (d == null) return;
        Library.Resume r = library.resume(d);
        if (r == null) {
            play(d, false);
            return;
        }
        Object[] options = {"Resume from " + PlayerWindow.time(r.time), "Start from the beginning", "Cancel"};
        int c = JOptionPane.showOptionDialog(this, d.title, "Play", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (c == 0) play(d, true);
        else if (c == 1) play(d, false);
    }

    private void play(Disc d, boolean resume) {
        if (!resume) library.setResume(d, null);
        new PlayerWindow(library, d, resume ? library.resume(d) : null, () -> {
            grid.repaint();
            grid.requestFocusInWindow();
        }).setVisible(true);
    }

    private JPopupMenu discMenu(Disc d) {
        JPopupMenu m = new JPopupMenu();
        JMenuItem play = new JMenuItem("Play");
        play.addActionListener(e -> openSelected());
        JMenuItem fresh = new JMenuItem("Play from the beginning");
        fresh.addActionListener(e -> play(d, false));
        JMenuItem forget = new JMenuItem("Forget where I stopped");
        forget.addActionListener(e -> {
            library.setResume(d, null);
            grid.repaint();
        });
        JMenuItem show = new JMenuItem("Show in File Explorer");
        show.addActionListener(e -> {
            try {
                new ProcessBuilder("explorer.exe", "/select,", d.path).start();
            } catch (Exception ex) {
                try {
                    Desktop.getDesktop().open(new File(d.path).getParentFile());
                } catch (Exception ignored) {
                }
            }
        });
        JMenuItem pick = new JMenuItem("Choose cover…");
        pick.addActionListener(e -> chooseCover(d));
        JMenuItem other = new JMenuItem("Try a different cover");
        other.addActionListener(e -> art.next(d));
        JMenuItem none = new JMenuItem("Show no cover");
        none.addActionListener(e -> art.hide(d));
        m.add(play);
        m.add(fresh);
        m.add(forget);
        m.addSeparator();
        m.add(pick);
        m.add(other);
        m.add(none);
        m.addSeparator();
        m.add(show);
        return m;
    }

    /** The owner picks the cover: found on Google, copied, and pasted here; or a picture file. */
    private void chooseCover(Disc d) {
        String help = "<html><body style='width:360px'>"
                + "<p><b>1.</b> Press <b>Search Google</b>. Your browser shows pictures for this disc.</p><br>"
                + "<p><b>2.</b> Click the right picture, then right-click the large version and choose <b>Copy image</b>.</p><br>"
                + "<p><b>3.</b> Come back here and press <b>Paste picture</b>.</p><br>"
                + "<p style='color:gray'>The cover is saved next to the disc.</p></body></html>";
        Object[] options = {"Search Google", "Paste picture", "Picture file…", "Cancel"};
        while (true) {
            int c = JOptionPane.showOptionDialog(this, help, "Cover for " + d.title, JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (c == 0) {
                try {
                    String q = java.net.URLEncoder.encode(d.title + " DVD cover", java.nio.charset.StandardCharsets.UTF_8);
                    Desktop.getDesktop().browse(new java.net.URI("https://www.google.com/search?tbm=isch&q=" + q));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "The browser could not be opened: " + ex.getMessage());
                }
                options[0] = "Search again";
                continue;
            }
            BufferedImage picture = null;
            try {
                if (c == 1) {
                    picture = pastedPicture();
                    if (picture == null) {
                        JOptionPane.showMessageDialog(this, "There's no picture to paste yet. In your browser, right-click"
                                + " the picture and choose Copy image, then press Paste picture again.");
                        continue;
                    }
                } else if (c == 2) {
                    JFileChooser fc = new JFileChooser(new File(d.path).getParentFile());
                    fc.setFileFilter(new FileNameExtensionFilter("Pictures", "jpg", "jpeg", "png", "gif", "bmp"));
                    if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) continue;
                    picture = javax.imageio.ImageIO.read(fc.getSelectedFile());
                    if (picture == null) {
                        JOptionPane.showMessageDialog(this, "That file isn't a picture Disc Shelf can read. Try a JPG or PNG.");
                        continue;
                    }
                } else {
                    return;
                }
                File saved = art.choose(d, picture);
                status.setText(saved != null ? "Cover saved as " + saved.getPath()
                        : "Cover saved on this PC only: the folder holding the disc can't be written to.");
                return;
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "The cover could not be used: " + ex.getMessage());
            }
        }
    }

    /** A picture on the clipboard: copied from a browser, copied as a file, or copied as a web address. */
    private BufferedImage pastedPicture() throws Exception {
        java.awt.datatransfer.Clipboard cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        if (cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) {
            java.awt.Image img = (java.awt.Image) cb.getData(java.awt.datatransfer.DataFlavor.imageFlavor);
            BufferedImage b = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = b.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            return b;
        }
        if (cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
            @SuppressWarnings("unchecked")
            List<File> files = (List<File>) cb.getData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            if (!files.isEmpty()) return javax.imageio.ImageIO.read(files.get(0));
        }
        if (cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)) {
            String text = ((String) cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor)).trim();
            if (text.startsWith("http://") || text.startsWith("https://")) return art.download(text);
        }
        return null;
    }

    // ------------------------------------------------------------ sources and settings

    private void addFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose a folder, drive or network share holding your discs");
        fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        fc.setAcceptAllFileFilterUsed(true);
        fc.addChoosableFileFilter(new FileNameExtensionFilter("Folders, or a list file (.txt, .m3u)", "txt", "m3u", "m3u8"));
        fc.setApproveButtonText("Add");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        addSource(f.getAbsolutePath());
    }

    private void addSource(String path) {
        List<String> s = library.sources();
        if (!s.contains(path)) s.add(path);
        library.setSources(s);
        rescan();
    }

    private void editSources() {
        DefaultListModel<String> m = new DefaultListModel<>();
        for (String s : library.sources()) m.addElement(s);
        JList<String> list = new JList<>(m);
        list.setVisibleRowCount(8);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(560, 220));

        JButton addFolder = new JButton("Add folder…");
        JButton addPath = new JButton("Type a path or share…");
        JButton remove = new JButton("Remove");
        addFolder.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) m.addElement(fc.getSelectedFile().getAbsolutePath());
        });
        addPath.addActionListener(e -> {
            String p = JOptionPane.showInputDialog(this,
                    "A folder, drive, network share or list file, for example\n\\\\192.168.1.4\\Movies   or   D:\\Discs",
                    "Add a source", JOptionPane.PLAIN_MESSAGE);
            if (p != null && !p.trim().isEmpty()) m.addElement(p.trim().replace('/', '\\'));
        });
        remove.addActionListener(e -> {
            int i = list.getSelectedIndex();
            if (i >= 0) m.remove(i);
        });

        JPanel buttons = new JPanel(new GridLayout(1, 3, 8, 0));
        buttons.add(addFolder);
        buttons.add(addPath);
        buttons.add(remove);
        JLabel hint = new JLabel("<html>Network shares: open the share once in File Explorer and tick "
                + "\"Remember my credentials\", so Disc Shelf can read it too.</html>");
        hint.setForeground(Color.GRAY);
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.add(sp, BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        p.add(hint, BorderLayout.NORTH);

        if (JOptionPane.showConfirmDialog(this, p, "Sources", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < m.size(); i++) out.add(m.get(i));
        library.setSources(out);
        rescan();
    }

    private void settings() {
        JComboBox<Settings.Sound> sound = new JComboBox<>(Settings.Sound.values());
        sound.setSelectedItem(Settings.sound());
        JCheckBox menus = new JCheckBox("Start discs on their own menu", Settings.menus());
        JCheckBox full = new JCheckBox("Play full screen", Settings.startFullScreen());
        JCheckBox covers = new JCheckBox("Find cover art online (Apple's iTunes film and TV catalogue)", Art.online());
        JTextField audio = new JTextField(Settings.audioLang(), 8);
        JTextField subs = new JTextField(Settings.subLang(), 8);

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(left(new JLabel("Sound output")));
        p.add(left(sound));
        JLabel soundHint = new JLabel("<html>HDMI bitstream needs Windows to send audio to the receiver over HDMI"
                + " (Sound settings, choose the receiver as the output).</html>");
        soundHint.setForeground(Color.GRAY);
        p.add(left(soundHint));
        p.add(Box.createVerticalStrut(12));
        p.add(left(menus));
        p.add(left(full));
        p.add(left(covers));
        JLabel coverHint = new JLabel("A picture saved beside a disc, such as The Matrix.jpg next to The Matrix.iso, is used first.");
        coverHint.setForeground(Color.GRAY);
        p.add(left(coverHint));
        p.add(Box.createVerticalStrut(12));
        JPanel langs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        langs.add(new JLabel("Preferred soundtrack language"));
        langs.add(audio);
        langs.add(new JLabel("Subtitles"));
        langs.add(subs);
        p.add(left(langs));
        JLabel langHint = new JLabel("Two or three letters, for example en or eng. Leave empty for the disc's choice.");
        langHint.setForeground(Color.GRAY);
        p.add(left(langHint));

        if (JOptionPane.showConfirmDialog(this, p, "Settings", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        Settings.setSound((Settings.Sound) sound.getSelectedItem());
        Settings.setMenus(menus.isSelected());
        Settings.setStartFullScreen(full.isSelected());
        if (covers.isSelected() != Art.online()) {
            Art.setOnline(covers.isSelected());
            art.retryMissing();
            grid.repaint();
        }
        Settings.setAudioLang(audio.getText());
        Settings.setSubLang(subs.getText());
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    // ------------------------------------------------------------ tiles

    private final class TileRenderer extends JComponent implements ListCellRenderer<Disc> {
        private Disc disc;
        private int index;
        private boolean selected;

        TileRenderer() {
            setPreferredSize(new Dimension(TILE_W, TILE_H));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Disc> list, Disc value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            this.disc = value;
            this.index = index;
            this.selected = isSelected;
            setToolTipText(value.title);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth() - 16, h = getHeight() - 16, x = 8, y = 8;
            Color[] c = TILE_COLOURS[Math.abs(disc.title.hashCode()) % TILE_COLOURS.length];
            g.setPaint(new GradientPaint(x, y, c[0], x, y + h, c[1]));
            g.fillRoundRect(x, y, w, h, 16, 16);

            BufferedImage cover = art.get(disc);
            if (cover != null) {
                /* Fill the tile, trimming whichever sides overflow, inside the rounded corners. */
                java.awt.Shape oldClip = g.getClip();
                g.clip(new RoundRectangle2D.Float(x, y, w, h, 16, 16));
                double scale = Math.max((double) w / cover.getWidth(), (double) h / cover.getHeight());
                int iw = (int) Math.ceil(cover.getWidth() * scale), ih = (int) Math.ceil(cover.getHeight() * scale);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(cover, x + (w - iw) / 2, y + (h - ih) / 2, iw, ih, null);
                g.setPaint(new GradientPaint(x, y + h - 60, new Color(0, 0, 0, 0), x, y + h, new Color(0, 0, 0, 220)));
                g.fillRect(x, y + h - 60, w, 60);
                g.setClip(oldClip);
            }
            if (selected) {
                g.setColor(ACCENT);
                g.setStroke(new java.awt.BasicStroke(3f));
                g.drawRoundRect(x + 1, y + 1, w - 2, h - 2, 16, 16);
            }

            g.setFont(getFont().deriveFont(Font.BOLD, 11f));
            String badge = disc.badge();
            int bw = g.getFontMetrics().stringWidth(badge) + 12;
            g.setColor(new Color(0, 0, 0, 150));
            g.fillRoundRect(x + 12, y + 12, bw, 20, 10, 10);
            g.setColor(new Color(230, 230, 230));
            g.drawString(badge, x + 18, y + 26);

            if (cover == null) {
                g.setFont(getFont().deriveFont(Font.BOLD, 16f));
                g.setColor(Color.WHITE);
                drawWrapped(g, disc.title, x + 14, y + 62, w - 28, 5);
            }

            g.setFont(getFont().deriveFont(12f));
            g.setColor(new Color(200, 200, 200));
            g.drawString(Disc.size(disc.size), x + 14, y + h - 14);

            Library.Resume r = library.resume(disc);
            if (r != null) {
                g.setColor(ACCENT);
                g.fillRoundRect(x + w - 70, y + h - 24, 56, 8, 4, 4);
                g.setFont(getFont().deriveFont(11f));
                g.drawString("Resume", x + w - 70, y + h - 30);
            }
            g.dispose();
        }

        private void drawWrapped(Graphics2D g, String text, int x, int y, int width, int maxLines) {
            java.awt.FontMetrics fm = g.getFontMetrics();
            String[] words = text.split(" ");
            StringBuilder line = new StringBuilder();
            int lines = 0;
            for (int i = 0; i < words.length; i++) {
                String tryLine = line.length() == 0 ? words[i] : line + " " + words[i];
                if (fm.stringWidth(tryLine) > width && line.length() > 0) {
                    lines++;
                    if (lines == maxLines) {
                        g.drawString(line + "…", x, y);
                        return;
                    }
                    g.drawString(line.toString(), x, y);
                    y += fm.getHeight();
                    line = new StringBuilder(words[i]);
                } else {
                    line = new StringBuilder(tryLine);
                }
            }
            g.drawString(line.toString(), x, y);
        }
    }
}
