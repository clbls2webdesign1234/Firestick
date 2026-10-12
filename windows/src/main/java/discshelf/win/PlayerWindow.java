package discshelf.win;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.ChapterDescription;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.TitleDescription;
import uk.co.caprica.vlcj.player.base.TrackDescription;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;
import uk.co.caprica.vlcj.player.component.InputEvents;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.fullscreen.adaptive.AdaptiveFullScreenStrategy;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plays one disc. Keyboard:
 *   Space pause · Left/Right back 10 s / forward 30 s (move in a disc menu)
 *   Up/Down/Enter move and choose in a disc menu · Page Up/Down previous/next chapter
 *   M disc menu · A soundtrack · S subtitles · C chapters · T titles · F full screen · Esc back
 */
final class PlayerWindow extends JFrame {
    private final Library library;
    private final Disc disc;
    private final Library.Resume resume;
    private final Runnable onClose;

    private final MediaPlayerFactory factory;
    private final EmbeddedMediaPlayerComponent component;
    private final EmbeddedMediaPlayer player;

    private final JPanel controls = new JPanel(new BorderLayout(10, 4));
    private final JSlider seek = new JSlider(0, 1000, 0);
    private final JLabel time = new JLabel("0:00:00 / 0:00:00");
    private final JLabel info = new JLabel(" ");
    private final JButton playPause = new JButton("Pause");
    private final JButton menuButton = new JButton("Disc menu");
    private final Timer hideControls = new Timer(3500, e -> setControlsVisible(false));
    private final KeyEventDispatcher keys = this::key;

    private boolean dragging;
    private boolean resumed;
    private boolean closed;
    private int menuTitle = -1;

    PlayerWindow(Library library, Disc disc, Library.Resume resume, Runnable onClose) {
        super(disc.title + " - Disc Shelf");
        this.library = library;
        this.disc = disc;
        this.resume = resume;
        this.onClose = onClose;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1280, 760);
        setLocationRelativeTo(null);
        getContentPane().setBackground(Color.BLACK);

        factory = new MediaPlayerFactory(vlcOptions());
        component = new EmbeddedMediaPlayerComponent(factory, null, new AdaptiveFullScreenStrategy(this),
                InputEvents.DISABLE_NATIVE, null);
        player = component.mediaPlayer();
        add(component, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);

        player.events().addMediaPlayerEventListener(new Events());
        MouseAdapter wake = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                setControlsVisible(true);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) player.fullScreen().toggle();
                else if (SwingUtilities.isRightMouseButton(e)) optionsMenu().show(e.getComponent(), e.getX(), e.getY());
            }
        };
        component.videoSurfaceComponent().addMouseListener(wake);
        component.videoSurfaceComponent().addMouseMotionListener(wake);
        hideControls.setRepeats(false);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys);
                start();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                shutDown();
            }
        });
    }

    // ------------------------------------------------------------ engine

    private List<String> vlcOptions() {
        List<String> o = new ArrayList<>();
        o.add("--no-video-title-show");
        o.add("--deinterlace=-1");
        o.add("--deinterlace-mode=yadif");
        o.add("--aout=mmdevice");
        switch (Settings.sound()) {
            case HDMI:
                // Sends DTS, Dolby Digital, Dolby TrueHD and DTS-HD MA untouched to the receiver.
                o.add("--mmdevice-passthrough=2");
                o.add("--spdif");
                break;
            case SPDIF:
                o.add("--mmdevice-passthrough=1");
                o.add("--spdif");
                break;
            default:
                o.add("--mmdevice-passthrough=0");
                o.add("--no-spdif");
                break;
        }
        if (!Settings.audioLang().isEmpty()) o.add("--audio-language=" + Settings.audioLang());
        if (!Settings.subLang().isEmpty()) o.add("--sub-language=" + Settings.subLang());
        return o;
    }

    private void start() {
        List<String> media = new ArrayList<>();
        if (!Settings.menus() || resume != null) {
            media.add(":no-dvdnav-menu");
            media.add(":no-bluray-menu");
        }
        if (Settings.startFullScreen()) player.fullScreen().set(true);
        info.setText("Opening " + disc.title + "…");
        if (!player.media().play(disc.mrl(), media.toArray(new String[0]))) {
            info.setText("This disc could not be opened: " + disc.path);
        }
        setControlsVisible(true);
    }

    private void shutDown() {
        if (closed) return;
        closed = true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys);
        hideControls.stop();
        saveResume();
        try {
            player.controls().stop();
        } catch (Throwable ignored) {
        }
        component.release();
        factory.release();
        onClose.run();
    }

    private void saveResume() {
        long t = player.status().time();
        long len = player.status().length();
        int title = player.titles().title();
        if (t > 60_000 && (len <= 0 || t < len - 5 * 60_000) && title != menuTitle) {
            Library.Resume r = new Library.Resume();
            r.time = t;
            r.title = title;
            library.setResume(disc, r);
        } else if (len > 0 && t >= len - 5 * 60_000) {
            library.setResume(disc, null);
        }
    }

    private final class Events extends MediaPlayerEventAdapter {
        @Override
        public void playing(MediaPlayer mp) {
            SwingUtilities.invokeLater(() -> {
                playPause.setText("Pause");
                refreshTitles();
                if (resume != null && !resumed) {
                    resumed = true;
                    if (resume.title >= 0 && resume.title != player.titles().title()) player.titles().setTitle(resume.title);
                    player.controls().setTime(resume.time);
                }
                new Timer(3000, e -> {
                    ((Timer) e.getSource()).stop();
                    detectKind();
                    updateInfo();
                }).start();
            });
        }

        @Override
        public void paused(MediaPlayer mp) {
            SwingUtilities.invokeLater(() -> {
                playPause.setText("Play");
                setControlsVisible(true);
            });
        }

        @Override
        public void titleChanged(MediaPlayer mp, int title) {
            SwingUtilities.invokeLater(() -> {
                refreshTitles();
                updateInfo();
            });
        }

        @Override
        public void chapterChanged(MediaPlayer mp, int chapter) {
            SwingUtilities.invokeLater(PlayerWindow.this::updateInfo);
        }

        @Override
        public void timeChanged(MediaPlayer mp, long t) {
            SwingUtilities.invokeLater(() -> {
                long len = player.status().length();
                time.setText(time(t) + " / " + time(len));
                if (!dragging && len > 0) seek.setValue((int) (t * 1000 / len));
            });
        }

        @Override
        public void finished(MediaPlayer mp) {
            SwingUtilities.invokeLater(() -> {
                library.setResume(disc, null);
                dispose();
            });
        }

        @Override
        public void error(MediaPlayer mp) {
            SwingUtilities.invokeLater(() -> {
                setControlsVisible(true);
                info.setText("Playback failed. Copy-protected Blu-ray images and damaged files cannot be played.");
            });
        }
    }

    private void refreshTitles() {
        menuTitle = -1;
        List<TitleDescription> titles = player.titles().titleDescriptions();
        for (int i = 0; titles != null && i < titles.size(); i++) {
            if (titles.get(i).isMenu()) {
                menuTitle = i;
                break;
            }
        }
        menuButton.setEnabled(menuTitle >= 0);
    }

    private boolean inMenu() {
        int cur = player.titles().title();
        if (menuTitle >= 0 && cur == menuTitle) return true;
        List<TitleDescription> titles = player.titles().titleDescriptions();
        return titles != null && cur >= 0 && cur < titles.size() && titles.get(cur).isMenu();
    }

    private void detectKind() {
        if (!disc.kind.isEmpty()) return;
        Dimension v = player.video().videoDimension();
        if (v == null || v.height <= 0) return;
        library.setKind(disc, v.height > 576 ? Disc.BLURAY : Disc.DVD);
    }

    private void updateInfo() {
        StringBuilder sb = new StringBuilder(disc.title);
        Dimension v = player.video().videoDimension();
        if (v != null && v.height > 0) sb.append("   ·   ").append(v.width).append("×").append(v.height);
        int chapters = player.chapters().count();
        if (chapters > 0) sb.append("   ·   Chapter ").append(player.chapters().chapter() + 1).append(" of ").append(chapters);
        String audio = currentName(player.audio().trackDescriptions(), player.audio().track());
        if (audio != null) sb.append("   ·   ").append(audio);
        if (inMenu()) sb.append("   ·   Disc menu: arrows and Enter");
        info.setText(sb.toString());
    }

    private static String currentName(List<TrackDescription> tracks, int id) {
        if (tracks == null) return null;
        for (TrackDescription t : tracks) if (t.id() == id) return t.description();
        return null;
    }

    // ------------------------------------------------------------ controls

    private JComponent buildControls() {
        controls.setBackground(new Color(0x111418));
        controls.setBorder(BorderFactory.createEmptyBorder(8, 16, 10, 16));

        seek.setFocusable(false);
        seek.setOpaque(false);
        seek.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragging = true;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = false;
                long len = player.status().length();
                if (len > 0) player.controls().setTime(len * seek.getValue() / 1000);
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(small("⏮ Chapter", () -> player.chapters().previous()));
        buttons.add(small("−10 s", () -> player.controls().skipTime(-10_000)));
        playPause.addActionListener(e -> player.controls().pause());
        playPause.setFocusable(false);
        buttons.add(playPause);
        buttons.add(small("+30 s", () -> player.controls().skipTime(30_000)));
        buttons.add(small("Chapter ⏭", () -> player.chapters().next()));
        menuButton.addActionListener(e -> discMenu());
        menuButton.setFocusable(false);
        buttons.add(menuButton);
        buttons.add(popupButton("Soundtrack", this::audioMenu));
        buttons.add(popupButton("Subtitles", this::subtitleMenu));
        buttons.add(popupButton("Chapters", this::chapterMenu));
        buttons.add(popupButton("Titles", this::titleMenu));
        buttons.add(small("Full screen", () -> player.fullScreen().toggle()));

        time.setForeground(Color.LIGHT_GRAY);
        time.setFont(time.getFont().deriveFont(Font.PLAIN, 13f));
        info.setForeground(Color.GRAY);

        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setOpaque(false);
        top.add(seek, BorderLayout.CENTER);
        top.add(time, BorderLayout.EAST);
        controls.add(top, BorderLayout.NORTH);
        controls.add(buttons, BorderLayout.CENTER);
        controls.add(info, BorderLayout.SOUTH);
        return controls;
    }

    private static JButton small(String text, Runnable r) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    private JButton popupButton(String text, java.util.function.Supplier<JPopupMenu> menu) {
        JButton b = new JButton(text + " ▾");
        b.setFocusable(false);
        b.addActionListener(e -> {
            JPopupMenu m = menu.get();
            m.show(b, 0, -m.getPreferredSize().height);
        });
        return b;
    }

    private void setControlsVisible(boolean on) {
        boolean full = player.fullScreen().isFullScreen();
        controls.setVisible(on || !full);
        if (on && full) hideControls.restart();
        getContentPane().setCursor(on || !full ? null
                : getToolkit().createCustomCursor(new java.awt.image.BufferedImage(1, 1,
                java.awt.image.BufferedImage.TYPE_INT_ARGB), new java.awt.Point(), "none"));
        revalidate();
    }

    private void discMenu() {
        if (menuTitle >= 0) {
            player.titles().setTitle(menuTitle);
        } else {
            info.setText("This disc has no menu the player can show. Use Titles and Chapters instead.");
        }
    }

    private JPopupMenu audioMenu() {
        return trackMenu("No soundtracks reported yet", player.audio().trackDescriptions(),
                player.audio().track(), id -> player.audio().setTrack(id));
    }

    private JPopupMenu subtitleMenu() {
        return trackMenu("No subtitles on this disc", player.subpictures().trackDescriptions(),
                player.subpictures().track(), id -> player.subpictures().setTrack(id));
    }

    private JPopupMenu trackMenu(String none, List<TrackDescription> tracks, int current,
                                 java.util.function.IntConsumer select) {
        JPopupMenu m = new JPopupMenu();
        if (tracks == null || tracks.isEmpty()) {
            JMenuItem n = new JMenuItem(none);
            n.setEnabled(false);
            m.add(n);
            return m;
        }
        for (TrackDescription t : tracks) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(t.description(), t.id() == current);
            item.addActionListener(e -> {
                select.accept(t.id());
                SwingUtilities.invokeLater(this::updateInfo);
            });
            m.add(item);
        }
        return m;
    }

    private JPopupMenu chapterMenu() {
        JPopupMenu m = new JPopupMenu();
        List<ChapterDescription> chapters = player.chapters().descriptions();
        int cur = player.chapters().chapter();
        if (chapters == null || chapters.isEmpty()) {
            JMenuItem n = new JMenuItem("No chapters in this title");
            n.setEnabled(false);
            m.add(n);
            return m;
        }
        for (int i = 0; i < chapters.size(); i++) {
            ChapterDescription c = chapters.get(i);
            String name = c.name() == null || c.name().isEmpty() ? "Chapter " + (i + 1) : c.name();
            final int index = i;
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(name + "   " + time(c.offset()), i == cur);
            item.addActionListener(e -> player.chapters().setChapter(index));
            m.add(item);
        }
        return m;
    }

    private JPopupMenu titleMenu() {
        JPopupMenu m = new JPopupMenu();
        List<TitleDescription> titles = player.titles().titleDescriptions();
        int cur = player.titles().title();
        if (titles == null || titles.isEmpty()) {
            JMenuItem n = new JMenuItem("No titles reported");
            n.setEnabled(false);
            m.add(n);
            return m;
        }
        for (int i = 0; i < titles.size(); i++) {
            TitleDescription t = titles.get(i);
            String name = t.isMenu() ? "Disc menu"
                    : (t.name() == null || t.name().isEmpty() ? "Title " + (i + 1) : t.name());
            if (t.duration() > 0) name += "   " + time(t.duration());
            final int index = i;
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(name, i == cur);
            item.addActionListener(e -> player.titles().setTitle(index));
            m.add(item);
        }
        return m;
    }

    private JPopupMenu optionsMenu() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem menu = new JMenuItem("Disc menu");
        menu.setEnabled(menuTitle >= 0);
        menu.addActionListener(e -> discMenu());
        m.add(menu);
        m.add(submenu("Soundtrack", audioMenu()));
        m.add(submenu("Subtitles", subtitleMenu()));
        m.add(submenu("Chapters", chapterMenu()));
        m.add(submenu("Titles", titleMenu()));
        m.addSeparator();
        JMenuItem full = new JMenuItem("Full screen");
        full.addActionListener(e -> player.fullScreen().toggle());
        m.add(full);
        JMenuItem close = new JMenuItem("Stop");
        close.addActionListener(e -> dispose());
        m.add(close);
        return m;
    }

    private static javax.swing.JMenu submenu(String name, JPopupMenu from) {
        javax.swing.JMenu sub = new javax.swing.JMenu(name);
        for (java.awt.Component c : from.getComponents()) sub.add(c);
        return sub;
    }

    // ------------------------------------------------------------ keyboard

    private boolean key(KeyEvent e) {
        if (e.getID() != KeyEvent.KEY_PRESSED || !isActive()) return false;
        boolean menu = inMenu();
        switch (e.getKeyCode()) {
            case KeyEvent.VK_SPACE:
                player.controls().pause();
                break;
            case KeyEvent.VK_UP:
                player.menu().up();
                break;
            case KeyEvent.VK_DOWN:
                player.menu().down();
                break;
            case KeyEvent.VK_LEFT:
                if (menu || e.isControlDown()) player.menu().left();
                else player.controls().skipTime(-10_000);
                break;
            case KeyEvent.VK_RIGHT:
                if (menu || e.isControlDown()) player.menu().right();
                else player.controls().skipTime(30_000);
                break;
            case KeyEvent.VK_ENTER:
                player.menu().activate();
                break;
            case KeyEvent.VK_PAGE_UP:
                player.chapters().previous();
                break;
            case KeyEvent.VK_PAGE_DOWN:
                player.chapters().next();
                break;
            case KeyEvent.VK_M:
                discMenu();
                break;
            case KeyEvent.VK_A:
                showAt(audioMenu());
                break;
            case KeyEvent.VK_S:
                showAt(subtitleMenu());
                break;
            case KeyEvent.VK_C:
                showAt(chapterMenu());
                break;
            case KeyEvent.VK_T:
                showAt(titleMenu());
                break;
            case KeyEvent.VK_F:
            case KeyEvent.VK_F11:
                player.fullScreen().toggle();
                setControlsVisible(true);
                break;
            case KeyEvent.VK_ESCAPE:
            case KeyEvent.VK_BACK_SPACE:
                if (player.fullScreen().isFullScreen() && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    player.fullScreen().set(false);
                    setControlsVisible(true);
                } else {
                    dispose();
                }
                break;
            default:
                return false;
        }
        setControlsVisible(true);
        return true;
    }

    private void showAt(JPopupMenu m) {
        setControlsVisible(true);
        m.show(controls, 40, -m.getPreferredSize().height);
    }

    static String time(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
