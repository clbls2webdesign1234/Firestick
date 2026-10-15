package com.discshelf.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Plays one disc image.
 *
 * Remote control map:
 *   In a disc menu      arrows move, Select chooses.
 *   During the film     Left/Right seek, Select pauses and shows the control bar,
 *                       Rewind/Fast-forward skip chapters (or seek, see Settings).
 *   Any time            the Menu button opens the options panel: disc menu, audio,
 *                       subtitles, chapters, titles, sound output, picture size.
 */
public class PlayerActivity extends Activity implements MediaPlayer.EventListener {

    static final String EXTRA_DISC = "disc";
    static final String EXTRA_RESUME = "resume";

    private static final int TICK_MS = 500;
    private static final int CONTROLS_MS = 6000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Disc disc;
    private boolean wantResume;

    private LibVLC vlc;
    private MediaPlayer player;
    private MediaSession session;

    private VLCVideoLayout video;
    private View loading;
    private TextView loadingText;
    private TextView hint;
    private View controls;
    private TextView timeText;
    private TextView lengthText;
    private TextView infoText;
    private SeekBar seek;
    private ImageButton btnPlay;
    private View btnDiscMenu;
    private View panel;
    private TextView panelTitle;
    private ListView panelList;
    private final PanelAdapter panelAdapter = new PanelAdapter();
    private final ArrayList<Page> pages = new ArrayList<>();

    /** Bumped whenever playback starts or stops, so delayed work can tell it is stale. */
    private int generation;
    private boolean menusActive;
    private boolean retriedWithoutMenus;
    private boolean started;
    private boolean ended;
    private MediaPlayer.Title[] titles;
    private int menuIdx = -1;
    /** Set from Playback details to rule things out one at a time when a disc misbehaves. */
    private boolean skipReadAhead, skipRateMatch;
    private int voutCount;
    private boolean inMenu;
    private boolean popupNav;
    private boolean kindKnown;
    private boolean isBluray;
    private Store.Resume pendingResume;
    private int resumeTries;
    private long lastSave;
    private long backPressedAt;
    private boolean userSeeking;
    private int pendingSeek;
    private long sessionDuration = -1;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        disc = Store.disc(this, getIntent().getStringExtra(EXTRA_DISC));
        if (disc == null) {
            finish();
            return;
        }
        wantResume = getIntent().getBooleanExtra(EXTRA_RESUME, false);
        isBluray = "Blu-ray".equals(disc.kind);
        kindKnown = !Util.empty(disc.kind);

        video = (VLCVideoLayout) findViewById(R.id.video);
        loading = findViewById(R.id.loading);
        loadingText = (TextView) findViewById(R.id.loading_text);
        hint = (TextView) findViewById(R.id.hint);
        controls = findViewById(R.id.controls);
        timeText = (TextView) findViewById(R.id.time);
        lengthText = (TextView) findViewById(R.id.length);
        infoText = (TextView) findViewById(R.id.info);
        seek = (SeekBar) findViewById(R.id.seek);
        btnPlay = (ImageButton) findViewById(R.id.btn_play);
        btnDiscMenu = findViewById(R.id.btn_disc_menu);
        panel = findViewById(R.id.panel);
        panelTitle = (TextView) findViewById(R.id.panel_title);
        panelList = (ListView) findViewById(R.id.panel_list);
        ((TextView) findViewById(R.id.disc_title)).setText(disc.title);

        panelList.setAdapter(panelAdapter);
        panelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (pages.isEmpty() || player == null) return;
                Page page = pages.get(pages.size() - 1);
                if (position < 0 || position >= page.rows.size()) return;
                page.selection = position;
                Row row = page.rows.get(position);
                if (row.action != null) row.action.run();
            }
        });

        bindButton(R.id.btn_prev, new Runnable() {
            @Override
            public void run() {
                skipChapter(-1);
            }
        });
        bindButton(R.id.btn_rew, new Runnable() {
            @Override
            public void run() {
                seekBy(-10000);
            }
        });
        bindButton(R.id.btn_play, new Runnable() {
            @Override
            public void run() {
                togglePause();
            }
        });
        bindButton(R.id.btn_fwd, new Runnable() {
            @Override
            public void run() {
                seekBy(30000);
            }
        });
        bindButton(R.id.btn_next, new Runnable() {
            @Override
            public void run() {
                skipChapter(1);
            }
        });
        bindButton(R.id.btn_disc_menu, new Runnable() {
            @Override
            public void run() {
                hideControls();
                goDiscMenu();
            }
        });
        bindButton(R.id.btn_audio, new Runnable() {
            @Override
            public void run() {
                openPanel();
                push(audioPage());
            }
        });
        bindButton(R.id.btn_subs, new Runnable() {
            @Override
            public void run() {
                openPanel();
                push(subtitlePage());
            }
        });
        bindButton(R.id.btn_chapters, new Runnable() {
            @Override
            public void run() {
                openPanel();
                push(chapterPage());
            }
        });
        bindButton(R.id.btn_more, new Runnable() {
            @Override
            public void run() {
                openPanel();
            }
        });

        seek.setMax(1000);
        seek.setKeyProgressIncrement(5);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || player == null) return;
                userSeeking = true;
                pendingSeek = progress;
                long len = player.getLength();
                if (len > 0) timeText.setText(Util.time(len * progress / 1000));
                handler.removeCallbacks(applySeek);
                handler.postDelayed(applySeek, 350);
                keepControls();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });

        setupSession();
    }

    private void bindButton(int id, final Runnable action) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                keepControls();
                if (player != null) action.run();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (disc == null) return;
        if (session != null) session.setActive(true);
        hint.setVisibility(View.GONE);
        controls.setVisibility(View.GONE);
        closePanel();
        userSeeking = false;
        startPlayback(Prefs.startAtMenu(this) && !retriedWithoutMenus);
        handler.postDelayed(tick, TICK_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            saveResume();
            /* Coming back from the Fire TV home screen picks up where this left off. */
            wantResume = true;
            stopPlayback();
        }
        if (session != null) session.setActive(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) {
            session.release();
            session = null;
        }
    }

    // ------------------------------------------------------------------ playback

    private void startPlayback(boolean menus) {
        generation++;
        menusActive = menus;
        started = false;
        ended = false;
        titles = null;
        menuIdx = -1;
        voutCount = 0;
        inMenu = false;
        popupNav = false;
        resumeTries = 0;
        pendingResume = wantResume ? Store.resume(this, disc.id) : null;
        showLoading("Opening " + disc.title + "…");

        boolean software = Prefs.softwareDecode(this) && !isBluray
                && (disc.size <= 0 || disc.size < 10L * 1024 * 1024 * 1024);

        ArrayList<String> options = new ArrayList<>();
        if (!Prefs.audioLang(this).isEmpty()) options.add("--audio-language=" + Prefs.audioLang(this));
        if (!Prefs.subLang(this).isEmpty()) options.add("--sub-language=" + Prefs.subLang(this));
        if (!disc.isLocalPath() && !skipReadAhead) {
            /*
             * Read ahead from the share in large pieces. The engine's default asks for 16 KB at a
             * time, and over Wi-Fi each request waits a round trip, so a DVD could not keep up even
             * on a fast, strong connection. 1 MB reads into a 32 MB read-ahead buffer fix that.
             */
            options.add("--prefetch-read-size=1048576");
            options.add("--prefetch-buffer-size=32768");
        }
        if (software) {
            options.add("--deinterlace=-1");
            options.add("--deinterlace-mode=yadif");
        }
        try {
            try {
                vlc = new LibVLC(this, options);
            } catch (Throwable t) {
                vlc = new LibVLC(this, new ArrayList<String>());
            }
            player = new MediaPlayer(vlc);
        } catch (Throwable t) {
            player = null;
            fail("The player engine could not start on this device.\n\n" + t);
            return;
        }

        player.setEventListener(this);
        player.setVideoTitleDisplay(MediaPlayer.Position.Disable, 0);
        applyAudioOutput();
        player.attachViews(video, null, true, false);
        player.setVideoScale(scaleType());

        Media media;
        if (disc.folder) {
            /* A disc copied as files: open it as a disc so its menus and titles work. */
            String scheme = "Blu-ray".equals(disc.kind) ? "bluray://" : "dvd://";
            media = new Media(vlc, Uri.parse(scheme + Uri.encode(disc.location, "/")));
        } else if (disc.isLocalPath()) {
            media = new Media(vlc, disc.location);
        } else {
            String url = disc.location;
            Source src = Store.source(this, disc.sourceId);
            if (src != null && url.toLowerCase(Locale.US).startsWith(Source.SMB.equals(src.type) ? "smb://" : "http")) {
                url = Net.withLogin(url, src.user, src.pass);
            }
            media = new Media(vlc, Uri.parse(url));
        }
        media.addOption(":network-caching=" + Prefs.netCache(this));
        if (!disc.folder && !disc.isLocalPath() && !isBluray && !skipReadAhead) {
            /*
             * The read-ahead above tells the engine the share can't seek quickly, and the DVD
             * reader then refuses to look inside the image, so the disc played as a plain video
             * file: the intros ran, but its menus never appeared. Naming the DVD reader first
             * skips that check; a Blu-ray image falls through to the Blu-ray reader as before.
             */
            media.addOption(":demux=dvdnav,any");
        }
        media.setHWDecoderEnabled(!software, false);
        if (!menus) {
            media.addOption(":no-dvdnav-menu");
            media.addOption(":no-bluray-menu");
        }
        player.setMedia(media);
        media.release();
        player.play();
        updatePlayIcon();
    }

    private void stopPlayback() {
        generation++;
        handler.removeCallbacks(resumeStep);
        handler.removeCallbacks(matchRate);
        handler.removeCallbacks(applySeek);
        if (player != null) {
            MediaPlayer p = player;
            player = null;
            p.setEventListener(null);
            try {
                p.stop();
                p.detachViews();
            } catch (Throwable ignored) {
            }
            p.release();
        }
        if (vlc != null) {
            vlc.release();
            vlc = null;
        }
    }

    @Override
    public void onEvent(MediaPlayer.Event event) {
        if (player == null) return;
        switch (event.type) {
            case MediaPlayer.Event.Opening:
                showLoading("Opening " + disc.title + "…");
                break;
            case MediaPlayer.Event.Playing:
                started = true;
                hideLoading();
                refreshTitles();
                updateNav();
                updatePlayIcon();
                updateSession();
                if (pendingResume != null) {
                    handler.removeCallbacks(resumeStep);
                    handler.postDelayed(resumeStep, 1500);
                }
                break;
            case MediaPlayer.Event.Paused:
                updatePlayIcon();
                updateSession();
                break;
            case MediaPlayer.Event.Vout:
                voutCount = event.getVoutCount();
                if (event.getVoutCount() > 0) {
                    hideLoading();
                    handler.removeCallbacks(matchRate);
                    handler.postDelayed(matchRate, 1500);
                }
                break;
            case MediaPlayer.Event.ESAdded:
            case MediaPlayer.Event.ESDeleted:
                refreshTitles();
                break;
            case MediaPlayer.Event.EndReached:
                ended = true;
                Store.saveResume(this, disc.id, null);
                finish();
                break;
            case MediaPlayer.Event.EncounteredError:
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        onPlaybackError();
                    }
                });
                break;
            default:
                break;
        }
    }

    private void onPlaybackError() {
        if (player == null) return;
        boolean hadMenus = menusActive;
        stopPlayback();
        if (hadMenus && !retriedWithoutMenus) {
            /* Some discs (Java menus on Blu-ray, damaged DVD menus) only play without them. */
            retriedWithoutMenus = true;
            startPlayback(false);
            showHint("This disc's own menu can't be shown, so the main title is starting. "
                    + "Press Menu for chapters, audio and subtitles.", 7000);
            return;
        }
        fail("Disc Shelf couldn't open “" + disc.title + "”.\n\n"
                + "• Check the file can still be reached: share switched on, USB drive plugged in.\n"
                + "• For a network share, check the user name and password under Sources.\n"
                + "• Copy-protected Blu-ray images can't be played. DVD images normally can.\n"
                + "• Over the network the server has to allow jumping around inside the file.");
    }

    private void fail(String message) {
        hideLoading();
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Can't play this disc")
                .setMessage(message)
                .setPositiveButton("Back to the shelf", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        finish();
                    }
                })
                .show();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                updateNav();
                if (controlsVisible()) updateProgress();
                long now = SystemClock.elapsedRealtime();
                if (now - lastSave > 10000) {
                    lastSave = now;
                    saveResume();
                    updateSession();
                    if (!kindKnown) refreshTitles();
                }
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    // ------------------------------------------------------------------ titles, menus, disc type

    private void refreshTitles() {
        if (player == null) return;
        try {
            titles = player.getTitles();
        } catch (Throwable t) {
            titles = null;
        }
        menuIdx = -1;
        if (titles != null) {
            for (int i = 0; i < titles.length; i++) {
                if (titles[i] != null && titles[i].isMenu()) {
                    menuIdx = i;
                    break;
                }
            }
        }
        if (btnDiscMenu != null) btnDiscMenu.setVisibility(menuIdx >= 0 ? View.VISIBLE : View.GONE);
        if (!kindKnown && titles != null && titles.length > 0) detectKind();
    }

    private void detectKind() {
        String menuName = menuIdx >= 0 ? titles[menuIdx].name : null;
        if ("Top Menu".equals(menuName)) {
            isBluray = true;
        } else if ("DVD Menu".equals(menuName)) {
            isBluray = false;
        } else {
            int h = 0;
            for (IMedia.Track t : freshTracks()) {
                if (t instanceof IMedia.VideoTrack) h = Math.max(h, ((IMedia.VideoTrack) t).height);
            }
            if (h <= 0) return;
            isBluray = h > 576;
        }
        kindKnown = true;
        Store.setKind(this, disc.id, isBluray ? "Blu-ray" : "DVD");
    }

    /** Works out whether the disc is showing one of its own menus right now. */
    private void updateNav() {
        if (player == null) return;
        int cur = player.getTitle();
        if (titles == null || cur >= titles.length) refreshTitles();
        boolean interactive = titles != null && cur >= 0 && cur < titles.length
                && titles[cur] != null && titles[cur].isInteractive();
        boolean nav = (menuIdx >= 0 && cur == menuIdx) || interactive;
        if (nav == inMenu) return;
        inMenu = nav;
        if (nav) {
            popupNav = false;
            hideControls();
            if (!panelVisible()) showHint("Disc menu: arrows to move, Select to choose. Menu button for more.", 5000);
        }
    }

    private void goDiscMenu() {
        if (player == null || menuIdx < 0) {
            showHint("This disc has no menu to go to", 2500);
            return;
        }
        player.setTitle(menuIdx);
    }

    // ------------------------------------------------------------------ resume

    private final Runnable resumeStep = new Runnable() {
        @Override
        public void run() {
            if (player == null || pendingResume == null) return;
            refreshTitles();
            final Store.Resume r = pendingResume;
            boolean titleReady = r.title < 0 || (titles != null && r.title < titles.length);
            if (!titleReady && ++resumeTries < 12) {
                handler.postDelayed(this, 700);
                return;
            }
            pendingResume = null;
            final int gen = generation;
            Runnable jump = new Runnable() {
                @Override
                public void run() {
                    if (player == null || gen != generation) return;
                    player.setTime(r.time);
                    showHint("Resumed at " + Util.time(r.time), 3000);
                }
            };
            if (titleReady && r.title >= 0 && player.getTitle() != r.title) {
                player.setTitle(r.title);
                handler.postDelayed(jump, 1500);
            } else {
                jump.run();
            }
        }
    };

    private void saveResume() {
        if (player == null || !started || ended || inMenu || pendingResume != null) return;
        long time = player.getTime();
        long len = player.getLength();
        /* Ignore trailers, logos and warnings: only long titles are worth resuming. */
        if (len < 10 * 60 * 1000 || time < 60 * 1000) return;
        if (time > len - 3 * 60 * 1000) {
            Store.saveResume(this, disc.id, null);
            return;
        }
        Store.Resume r = new Store.Resume();
        r.title = player.getTitle();
        r.time = time;
        r.length = len;
        Store.saveResume(this, disc.id, r);
    }

    // ------------------------------------------------------------------ transport

    private void togglePause() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            showHint("Paused", 1500);
        } else {
            player.play();
        }
    }

    private void seekBy(long deltaMs) {
        if (player == null) return;
        long len = player.getLength();
        if (inMenu || len <= 0 || !player.isSeekable()) {
            showHint("Can't skip here", 1500);
            return;
        }
        long target = Math.max(0, Math.min(len - 2000, player.getTime() + deltaMs));
        player.setTime(target);
        showHint((deltaMs < 0 ? "«  " : "»  ") + Util.time(target) + " / " + Util.time(len), 2000);
    }

    private int chapterCount() {
        if (player == null) return 0;
        try {
            MediaPlayer.Chapter[] c = player.getChapters(-1);
            return c == null ? 0 : c.length;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void skipChapter(int direction) {
        if (player == null) return;
        if (inMenu) {
            showHint("Choose from the disc menu with the arrows", 2000);
            return;
        }
        final int count = chapterCount();
        if (count <= 1) {
            /* No chapter marks on this title: jump five minutes instead. */
            seekBy(direction * 5 * 60 * 1000L);
            return;
        }
        int target = player.getChapter() + direction;
        if (target < 0) target = 0;
        if (target >= count) {
            showHint("That was the last chapter", 2000);
            return;
        }
        player.setChapter(target);
        final int gen = generation;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (player == null || gen != generation) return;
                showHint("Chapter " + (player.getChapter() + 1) + " of " + count, 2000);
            }
        }, 450);
    }

    /** The remote's rewind and fast-forward buttons: chapter skip or seek, as set in Settings. */
    private void skipKey(int direction, int repeat) {
        if (Prefs.skipKeysAreChapters(this)) {
            if (repeat == 0) skipChapter(direction);
        } else {
            seekBy(direction * seekStep(direction > 0 ? 30000 : 10000, repeat));
        }
    }

    /** Holding a key down speeds the seek up. */
    private static long seekStep(long base, int repeat) {
        return Math.min(120000, base * (1 + repeat / 4));
    }

    private final Runnable applySeek = new Runnable() {
        @Override
        public void run() {
            if (player != null) player.setPosition(pendingSeek / 1000f);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    userSeeking = false;
                }
            }, 800);
        }
    };

    // ------------------------------------------------------------------ remote control keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (player == null) return super.dispatchKeyEvent(e);
        final int code = e.getKeyCode();
        final boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        final boolean up = e.getAction() == KeyEvent.ACTION_UP;
        final boolean first = down && e.getRepeatCount() == 0;

        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (first) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (first && !player.isPlaying()) player.play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (first && player.isPlaying()) player.pause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (first) finish();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (first) skipChapter(1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (first) skipChapter(-1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) skipKey(1, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) skipKey(-1, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (first) {
                    if (panelVisible()) closePanel();
                    else openPanel();
                }
                return true;
            default:
                break;
        }

        if (panelVisible()) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (up) panelBack();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (first) panelBack();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        if (controlsVisible()) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (up) hideControls();
                return true;
            }
            if (down) keepControls();
            return super.dispatchKeyEvent(e);
        }

        if (inMenu || popupNav) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down) player.navigate(MediaPlayer.Navigate.Up);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) player.navigate(MediaPlayer.Navigate.Down);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) player.navigate(MediaPlayer.Navigate.Left);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) player.navigate(MediaPlayer.Navigate.Right);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    if (first) player.navigate(MediaPlayer.Navigate.Activate);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (up) {
                        if (popupNav && !inMenu) {
                            popupNav = false;
                            showHint("Back to the film controls", 2000);
                        } else {
                            long now = SystemClock.elapsedRealtime();
                            if (now - backPressedAt < 3000) {
                                finish();
                            } else {
                                backPressedAt = now;
                                showHint("Press Back again to leave this disc", 3000);
                            }
                        }
                    }
                    return true;
                default:
                    return super.dispatchKeyEvent(e);
            }
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) seekBy(-seekStep(10000, e.getRepeatCount()));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) seekBy(seekStep(30000, e.getRepeatCount()));
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (first) {
                    togglePause();
                    showControls();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (first) showControls();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (up) finish();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    // ------------------------------------------------------------------ on-screen control bar

    private boolean controlsVisible() {
        return controls.getVisibility() == View.VISIBLE;
    }

    private void showControls() {
        if (inMenu || panelVisible()) return;
        hint.setVisibility(View.GONE);
        controls.setVisibility(View.VISIBLE);
        updateProgress();
        btnPlay.requestFocus();
        keepControls();
    }

    private void hideControls() {
        handler.removeCallbacks(hideControlsRunnable);
        controls.setVisibility(View.GONE);
    }

    private void keepControls() {
        handler.removeCallbacks(hideControlsRunnable);
        handler.postDelayed(hideControlsRunnable, CONTROLS_MS);
    }

    private final Runnable hideControlsRunnable = new Runnable() {
        @Override
        public void run() {
            /* Stay up while paused so the position remains readable. */
            if (player != null && !player.isPlaying() && !inMenu) {
                handler.postDelayed(this, CONTROLS_MS);
                return;
            }
            hideControls();
        }
    };

    private void updateProgress() {
        if (player == null) return;
        long len = player.getLength();
        long t = player.getTime();
        if (!userSeeking) {
            timeText.setText(Util.time(t));
            if (len > 0) seek.setProgress((int) (t * 1000 / len));
        }
        lengthText.setText(len > 0 ? Util.time(len) : "--:--:--");
        StringBuilder sb = new StringBuilder();
        int title = player.getTitle();
        if (titles != null && titles.length > 1 && title >= 0) sb.append("Title ").append(titleLabel(title));
        int chapters = chapterCount();
        if (chapters > 1) {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append("Chapter ").append(player.getChapter() + 1).append(" of ").append(chapters);
        }
        String audio = currentName(player.getAudioTracks(), player.getAudioTrack());
        if (audio != null) {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append("Audio: ").append(audio);
        }
        infoText.setText(sb.toString());
    }

    private void updatePlayIcon() {
        boolean playing = player != null && player.isPlaying();
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        btnPlay.setContentDescription(playing ? "Pause" : "Play");
    }

    private void showLoading(String text) {
        loadingText.setText(text);
        loading.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        loading.setVisibility(View.GONE);
    }

    private void showHint(String text, int ms) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideHint);
        handler.postDelayed(hideHint, ms);
    }

    private final Runnable hideHint = new Runnable() {
        @Override
        public void run() {
            hint.setVisibility(View.GONE);
        }
    };

    // ------------------------------------------------------------------ options panel

    private static final class Row {
        final String label;
        final String value;
        final boolean checked;
        final boolean more;
        final Runnable action;

        Row(String label, String value, boolean checked, boolean more, Runnable action) {
            this.label = label;
            this.value = value;
            this.checked = checked;
            this.more = more;
            this.action = action;
        }
    }

    private static final class Page {
        final String title;
        final List<Row> rows = new ArrayList<>();
        int selection;

        Page(String title) {
            this.title = title;
        }

        void link(String label, String value, Runnable action) {
            rows.add(new Row(label, value, false, true, action));
        }

        void choice(String label, String value, boolean checked, Runnable action) {
            rows.add(new Row(label, value, checked, false, action));
            if (checked) selection = rows.size() - 1;
        }

        void note(String text) {
            rows.add(new Row(text, null, false, false, null));
        }
    }

    private final class PanelAdapter extends BaseAdapter {
        private List<Row> rows = new ArrayList<>();

        void show(List<Row> rows) {
            this.rows = rows;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean isEnabled(int position) {
            return rows.get(position).action != null;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(PlayerActivity.this).inflate(R.layout.item_panel, parent, false);
            Row r = rows.get(position);
            TextView label = (TextView) v.findViewById(R.id.row_label);
            TextView value = (TextView) v.findViewById(R.id.row_value);
            label.setText(r.label);
            label.setAlpha(r.action == null ? 0.6f : 1f);
            value.setText(r.value == null ? "" : r.value);
            value.setVisibility(Util.empty(r.value) ? View.GONE : View.VISIBLE);
            v.findViewById(R.id.row_check).setVisibility(r.checked ? View.VISIBLE : View.INVISIBLE);
            v.findViewById(R.id.row_more).setVisibility(r.more ? View.VISIBLE : View.GONE);
            return v;
        }
    }

    private boolean panelVisible() {
        return panel.getVisibility() == View.VISIBLE;
    }

    private void openPanel() {
        if (player == null) return;
        hideControls();
        hint.setVisibility(View.GONE);
        pages.clear();
        pages.add(rootPage());
        panel.setVisibility(View.VISIBLE);
        showTopPage();
        panelList.requestFocus();
    }

    private void closePanel() {
        pages.clear();
        panel.setVisibility(View.GONE);
    }

    private void push(Page page) {
        pages.add(page);
        showTopPage();
    }

    private void replaceTop(Page page) {
        if (pages.isEmpty()) return;
        page.selection = pages.get(pages.size() - 1).selection;
        pages.set(pages.size() - 1, page);
        showTopPage();
    }

    private void panelBack() {
        if (pages.size() <= 1) {
            closePanel();
            return;
        }
        pages.remove(pages.size() - 1);
        if (pages.size() == 1) {
            /* Rebuild the first page so it shows what was just chosen. */
            int sel = pages.get(0).selection;
            Page root = rootPage();
            root.selection = Math.min(sel, root.rows.size() - 1);
            pages.set(0, root);
        }
        showTopPage();
    }

    private void showTopPage() {
        if (pages.isEmpty()) return;
        Page page = pages.get(pages.size() - 1);
        panelTitle.setText(page.title);
        panelAdapter.show(page.rows);
        panelList.setSelection(Math.max(0, page.selection));
    }

    private Page rootPage() {
        Page p = new Page(disc.title);
        if (menuIdx >= 0 && !inMenu) {
            p.link("Disc menu", null, new Runnable() {
                @Override
                public void run() {
                    closePanel();
                    goDiscMenu();
                }
            });
        }
        if (inMenu && !isBluray && menuIdx >= 0) {
            p.link("Return to the film", null, new Runnable() {
                @Override
                public void run() {
                    closePanel();
                    /* On a DVD menu, entry 0 is the disc's own "resume" call. */
                    player.setChapter(0);
                }
            });
        }
        if (isBluray && menuIdx >= 0 && !inMenu) {
            p.link("Pop-up menu", null, new Runnable() {
                @Override
                public void run() {
                    closePanel();
                    player.navigate(MediaPlayer.Navigate.Activate);
                    popupNav = true;
                    showHint("Pop-up menu: arrows and Select. Back when finished.", 5000);
                }
            });
        }
        p.link("Audio", currentAudioSummary(), new Runnable() {
            @Override
            public void run() {
                push(audioPage());
            }
        });
        p.link("Subtitles", currentName(player.getSpuTracks(), player.getSpuTrack()), new Runnable() {
            @Override
            public void run() {
                push(subtitlePage());
            }
        });
        int chapters = chapterCount();
        p.link(inMenu && !isBluray ? "Menu shortcuts" : "Chapters",
                inMenu || chapters <= 1 ? null : (player.getChapter() + 1) + " of " + chapters,
                new Runnable() {
                    @Override
                    public void run() {
                        push(chapterPage());
                    }
                });
        p.link("Titles", titles == null || player.getTitle() < 0 ? null : titleLabel(player.getTitle()), new Runnable() {
            @Override
            public void run() {
                push(titlePage());
            }
        });
        p.link("Sound output", outputLabel(Prefs.audioOut(this)), new Runnable() {
            @Override
            public void run() {
                push(outputPage());
            }
        });
        p.link("Audio delay", (player.getAudioDelay() / 1000) + " ms", new Runnable() {
            @Override
            public void run() {
                push(delayPage());
            }
        });
        p.link("Picture size", scaleLabel(scaleType()), new Runnable() {
            @Override
            public void run() {
                push(scalePage());
            }
        });
        p.link("Playback details", null, new Runnable() {
            @Override
            public void run() {
                push(detailsPage());
            }
        });
        p.link("Stop and return to the shelf", null, new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
        return p;
    }

    /** What the engine made of the disc, for a photo when something goes wrong. */
    private Page detailsPage() {
        Page p = new Page("Playback details");
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        p.note("Disc Shelf " + version + (isBluray ? ", Blu-ray" : ", DVD") + (disc.isLocalPath() ? ", local" : ", network"));
        int count = titles == null ? 0 : titles.length;
        p.note("Titles: " + count + ", now " + player.getTitle()
                + ", menu " + (menuIdx >= 0 ? "title " + menuIdx : "not found")
                + (inMenu ? ", in menu" : ""));
        p.note(count > 0 && menuIdx >= 0 ? "Opened as a disc, with menus"
                : count > 0 ? "Opened as a disc, no menu listed" : "Opened as a plain video file");
        p.note("Chapters: " + chapterCount() + ", picture outputs: " + voutCount
                + ", playing: " + player.isPlaying());
        for (IMedia.Track t : freshTracks()) {
            if (!(t instanceof IMedia.VideoTrack)) continue;
            IMedia.VideoTrack v = (IMedia.VideoTrack) t;
            String fps = v.frameRateDen > 0 ? String.format(Locale.US, "%.2f", v.frameRateNum / (float) v.frameRateDen) : "?";
            p.note("Video: " + v.width + "×" + v.height + ", " + fps + " fps, " + t.codec);
        }
        p.note("Read-ahead " + (skipReadAhead ? "off" : "on") + ", refresh-rate change "
                + (skipRateMatch || !Prefs.matchRate(this) ? "off" : "on")
                + ", decoding " + (Prefs.softwareDecode(this) ? "software" : "hardware")
                + ", buffer " + Prefs.netCache(this) / 1000.0 + " s");
        if (!disc.isLocalPath()) {
            p.link(skipReadAhead ? "Restart with read-ahead" : "Restart without read-ahead", null, new Runnable() {
                @Override
                public void run() {
                    skipReadAhead = !skipReadAhead;
                    restartForTest();
                }
            });
        }
        p.link(skipRateMatch ? "Restart with refresh-rate change" : "Restart without refresh-rate change", null, new Runnable() {
            @Override
            public void run() {
                skipRateMatch = !skipRateMatch;
                restartForTest();
            }
        });
        return p;
    }

    private void restartForTest() {
        closePanel();
        boolean menus = menusActive;
        stopPlayback();
        startPlayback(menus);
    }

    private Page audioPage() {
        Page p = new Page("Audio");
        MediaPlayer.TrackDescription[] tracks = player.getAudioTracks();
        int current = player.getAudioTrack();
        IMedia.Track[] info = freshTracks();
        if (tracks == null || tracks.length == 0) {
            p.note("No soundtracks are listed yet. Start the film, then look again.");
            return p;
        }
        for (final MediaPlayer.TrackDescription td : tracks) {
            final String label = td.id < 0 ? "Off" : cleanName(td.name);
            p.choice(label, td.id < 0 ? null : audioDetail(info, td.id), td.id == current, new Runnable() {
                @Override
                public void run() {
                    player.setAudioTrack(td.id);
                    showHint("Audio: " + label, 2500);
                    closePanel();
                }
            });
        }
        p.note("Channel counts appear once a track has been playing.");
        return p;
    }

    private Page subtitlePage() {
        Page p = new Page("Subtitles");
        MediaPlayer.TrackDescription[] tracks = player.getSpuTracks();
        int current = player.getSpuTrack();
        if (tracks == null || tracks.length == 0) {
            p.note("This title has no subtitles.");
            return p;
        }
        for (final MediaPlayer.TrackDescription td : tracks) {
            final String label = td.id < 0 ? "Off" : cleanName(td.name);
            p.choice(label, null, td.id == current, new Runnable() {
                @Override
                public void run() {
                    player.setSpuTrack(td.id);
                    showHint("Subtitles: " + label, 2500);
                    closePanel();
                }
            });
        }
        return p;
    }

    private Page chapterPage() {
        boolean menuShortcuts = inMenu && !isBluray;
        Page p = new Page(menuShortcuts ? "Menu shortcuts" : "Chapters");
        MediaPlayer.Chapter[] chapters = null;
        try {
            chapters = player.getChapters(-1);
        } catch (Throwable ignored) {
        }
        if (chapters == null || chapters.length == 0) {
            p.note("This title has no chapter marks.");
            return p;
        }
        int current = player.getChapter();
        for (int i = 0; i < chapters.length; i++) {
            final int index = i;
            MediaPlayer.Chapter c = chapters[i];
            String name = c == null || Util.empty(c.name) ? "Chapter " + (i + 1) : c.name;
            String at = menuShortcuts || c == null ? null : Util.time(c.timeOffset);
            p.choice(name, at, !menuShortcuts && i == current, new Runnable() {
                @Override
                public void run() {
                    player.setChapter(index);
                    closePanel();
                }
            });
        }
        return p;
    }

    private Page titlePage() {
        Page p = new Page("Titles");
        refreshTitles();
        if (titles == null || titles.length == 0) {
            p.note("The disc has not listed its titles yet.");
            return p;
        }
        int longest = -1;
        long best = 0;
        for (int i = 0; i < titles.length; i++) {
            if (titles[i] != null && titles[i].duration > best) {
                best = titles[i].duration;
                longest = i;
            }
        }
        int current = player.getTitle();
        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            MediaPlayer.Title t = titles[i];
            String value = t != null && t.duration > 0 ? Util.time(t.duration) : null;
            if (i == longest && value != null && titles.length > 2) value += "  \u00b7  main feature";
            p.choice(titleLabel(i), value, i == current, new Runnable() {
                @Override
                public void run() {
                    player.setTitle(index);
                    closePanel();
                }
            });
        }
        return p;
    }

    private String titleLabel(int i) {
        if (titles == null || i < 0 || i >= titles.length || titles[i] == null) return String.valueOf(i);
        if (titles[i].isMenu()) return "Disc menu";
        return Util.empty(titles[i].name) ? "Title " + i : titles[i].name;
    }

    private Page outputPage() {
        Page p = new Page("Sound output");
        String current = Prefs.audioOut(this);
        String[] modes = {Prefs.OUT_AUTO, Prefs.OUT_FORCE, Prefs.OUT_PCM, Prefs.OUT_STEREO};
        String[] details = {
                "Dolby and DTS go to the receiver untouched when it reports it can take them",
                "Send the formats ticked in Settings untouched, whatever the receiver reports",
                "The Fire TV decodes and sends up to 7.1 as plain PCM",
                "Mixed down to two channels, for TV speakers"};
        for (int i = 0; i < modes.length; i++) {
            final String mode = modes[i];
            p.choice(outputLabel(mode), details[i], mode.equals(current), new Runnable() {
                @Override
                public void run() {
                    Prefs.get(PlayerActivity.this).edit().putString(Prefs.AUDIO_OUT, mode).apply();
                    applyAudioOutput();
                    showHint("Sound output: " + outputLabel(mode), 2500);
                    closePanel();
                }
            });
        }
        return p;
    }

    static String outputLabel(String mode) {
        if (Prefs.OUT_FORCE.equals(mode)) return "Bitstream, forced";
        if (Prefs.OUT_PCM.equals(mode)) return "Multichannel PCM";
        if (Prefs.OUT_STEREO.equals(mode)) return "Stereo";
        return "Bitstream, automatic";
    }

    private Page delayPage() {
        final long delay = player.getAudioDelay();
        Page p = new Page("Audio delay: " + (delay / 1000) + " ms");
        p.choice("Sound later  (+50 ms)", null, false, new Runnable() {
            @Override
            public void run() {
                player.setAudioDelay(delay + 50000);
                replaceTop(delayPage());
            }
        });
        p.choice("Sound earlier  (−50 ms)", null, false, new Runnable() {
            @Override
            public void run() {
                player.setAudioDelay(delay - 50000);
                replaceTop(delayPage());
            }
        });
        p.choice("Reset to 0", null, false, new Runnable() {
            @Override
            public void run() {
                player.setAudioDelay(0);
                replaceTop(delayPage());
            }
        });
        p.note("Use this if lips and voices are out of step.");
        return p;
    }

    private Page scalePage() {
        Page p = new Page("Picture size");
        MediaPlayer.ScaleType current = scaleType();
        for (final MediaPlayer.ScaleType type : MediaPlayer.ScaleType.values()) {
            p.choice(scaleLabel(type), null, type == current, new Runnable() {
                @Override
                public void run() {
                    Prefs.get(PlayerActivity.this).edit().putString(Prefs.SCALE, type.name()).apply();
                    player.setVideoScale(type);
                    closePanel();
                }
            });
        }
        return p;
    }

    private MediaPlayer.ScaleType scaleType() {
        try {
            return MediaPlayer.ScaleType.valueOf(Prefs.scale(this));
        } catch (Exception e) {
            return MediaPlayer.ScaleType.SURFACE_BEST_FIT;
        }
    }

    private static String scaleLabel(MediaPlayer.ScaleType type) {
        switch (type) {
            case SURFACE_FIT_SCREEN:
                return "Zoom to fill (crops the edges)";
            case SURFACE_FILL:
                return "Stretch to fill";
            case SURFACE_16_9:
                return "Force 16:9";
            case SURFACE_4_3:
                return "Force 4:3";
            case SURFACE_ORIGINAL:
                return "Original size";
            default:
                return "Fit the screen";
        }
    }

    // ------------------------------------------------------------------ track naming

    private static String cleanName(String name) {
        return Util.empty(name) ? "Track" : name.trim();
    }

    private static String currentName(MediaPlayer.TrackDescription[] tracks, int id) {
        if (tracks == null) return null;
        for (MediaPlayer.TrackDescription td : tracks) {
            if (td.id == id) return td.id < 0 ? "Off" : cleanName(td.name);
        }
        return null;
    }

    private String currentAudioSummary() {
        int id = player.getAudioTrack();
        String name = currentName(player.getAudioTracks(), id);
        if (name == null || id < 0) return name;
        String detail = audioDetail(freshTracks(), id);
        return detail == null ? name : name + "  " + detail;
    }

    /**
     * libVLC keeps the first track list it read for a disc, and discs reveal their
     * soundtracks as they play, so clear that cache before asking again. If a future
     * engine version renames the field this quietly falls back to the cached list.
     */
    private IMedia.Track[] freshTracks() {
        if (player == null) return new IMedia.Track[0];
        IMedia media = player.getMedia();
        if (media == null) return new IMedia.Track[0];
        try {
            try {
                Field cache = media.getClass().getDeclaredField("mNativeTracks");
                cache.setAccessible(true);
                synchronized (media) {
                    cache.set(media, null);
                }
            } catch (Throwable ignored) {
            }
            int n = media.getTrackCount();
            IMedia.Track[] out = new IMedia.Track[n];
            for (int i = 0; i < n; i++) out[i] = media.getTrack(i);
            return out;
        } catch (Throwable t) {
            return new IMedia.Track[0];
        } finally {
            media.release();
        }
    }

    /** "Dolby Digital 5.1", "DTS-HD MA 7.1" and so on, or null when the disc gives no detail. */
    private static String audioDetail(IMedia.Track[] info, int id) {
        for (IMedia.Track t : info) {
            if (!(t instanceof IMedia.AudioTrack) || t.id != id) continue;
            IMedia.AudioTrack a = (IMedia.AudioTrack) t;
            String codec = codecName(a.codec, a.profile);
            String layout = channelName(a.channels);
            if (codec == null) return layout;
            return layout == null ? codec : codec + " " + layout;
        }
        return null;
    }

    private static String codecName(String description, int profile) {
        if (Util.empty(description)) return null;
        String d = description.toLowerCase(Locale.US);
        if (d.contains("truehd")) return "Dolby TrueHD";
        if (d.contains("e-ac3") || d.contains("eac3") || d.contains("a/52 b") || d.contains("a52b")) return "Dolby Digital Plus";
        if (d.contains("a52") || d.contains("a/52") || d.contains("ac3") || d.contains("ac-3")) return "Dolby Digital";
        if (d.contains("dts")) {
            switch (profile) {
                case 30:
                    return "DTS-ES";
                case 40:
                    return "DTS 96/24";
                case 50:
                    return "DTS-HD HRA";
                case 60:
                    return "DTS-HD MA";
                case 70:
                    return "DTS Express";
                default:
                    return "DTS";
            }
        }
        if (d.contains("lpcm") || d.contains("pcm")) return "PCM";
        if (d.contains("mlp")) return "MLP";
        if (d.contains("aac")) return "AAC";
        if (d.contains("mpeg audio") || d.contains("mpga") || d.contains("mp3") || d.contains("mp2")) return "MPEG audio";
        return description;
    }

    private static String channelName(int channels) {
        switch (channels) {
            case 1:
                return "mono";
            case 2:
                return "stereo";
            case 3:
                return "2.1";
            case 4:
                return "4.0";
            case 5:
                return "5.0";
            case 6:
                return "5.1";
            case 7:
                return "6.1";
            case 8:
                return "7.1";
            default:
                return channels > 8 ? channels + " ch" : null;
        }
    }

    // ------------------------------------------------------------------ sound output

    /**
     * Chooses how audio leaves the Fire TV. "encoded:<mask>" tells the engine which
     * formats to hand over untouched (bitstream); anything not in the mask is decoded.
     */
    private void applyAudioOutput() {
        if (player == null) return;
        String mode = Prefs.audioOut(this);
        String device;
        if (Prefs.OUT_STEREO.equals(mode)) {
            device = "stereo";
        } else if (Prefs.OUT_PCM.equals(mode)) {
            device = "pcm";
        } else if (Prefs.OUT_FORCE.equals(mode)) {
            long mask = formatMask(Prefs.forceFormats(this));
            device = mask == 0 ? "pcm" : "encoded:" + mask;
        } else {
            long[] sink = sinkAbilities();
            if (sink[0] != 0) device = "encoded:" + sink[0];
            else device = sink[1] > 2 ? "pcm" : "stereo";
        }
        try {
            player.setAudioOutputDevice(device);
        } catch (Throwable ignored) {
        }
    }

    private static long formatMask(Set<String> formats) {
        long mask = 0;
        if (formats.contains("ac3")) mask |= 1L << AudioFormat.ENCODING_AC3;
        if (formats.contains("eac3")) mask |= 1L << AudioFormat.ENCODING_E_AC3;
        if (formats.contains("dts")) mask |= 1L << AudioFormat.ENCODING_DTS;
        if (formats.contains("dtshd")) mask |= 1L << AudioFormat.ENCODING_DTS_HD;
        if (formats.contains("truehd")) mask |= 1L << AudioFormat.ENCODING_DOLBY_TRUEHD;
        return mask;
    }

    /** What the TV or receiver on HDMI says it accepts: [bitstream format mask, most PCM channels]. */
    private long[] sinkAbilities() {
        long mask = 0;
        long channels = 2;
        if (Build.VERSION.SDK_INT < 23) return new long[]{mask, channels};
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (!d.isSink()) continue;
                for (int enc : d.getEncodings()) {
                    if (enc == AudioFormat.ENCODING_AC3 || enc == AudioFormat.ENCODING_E_AC3
                            || enc == AudioFormat.ENCODING_DTS || enc == AudioFormat.ENCODING_DTS_HD
                            || enc == AudioFormat.ENCODING_DOLBY_TRUEHD) {
                        mask |= 1L << enc;
                    }
                }
                for (int count : d.getChannelCounts()) channels = Math.max(channels, count);
            }
        } catch (Throwable ignored) {
        }
        return new long[]{mask, channels};
    }

    // ------------------------------------------------------------------ display refresh rate

    /** Switches the TV to a refresh rate that suits the disc: 50 Hz for PAL, 24 Hz for film. */
    private final Runnable matchRate = new Runnable() {
        @Override
        public void run() {
            if (player == null || skipRateMatch || !Prefs.matchRate(PlayerActivity.this)) return;
            float fps = 0;
            int selected = player.getVideoTrack();
            for (IMedia.Track t : freshTracks()) {
                if (!(t instanceof IMedia.VideoTrack)) continue;
                IMedia.VideoTrack v = (IMedia.VideoTrack) t;
                if (v.frameRateDen <= 0 || v.frameRateNum <= 0) continue;
                float f = v.frameRateNum / (float) v.frameRateDen;
                if (fps == 0 || t.id == selected) fps = f;
            }
            if (fps < 20 || fps > 121 || Build.VERSION.SDK_INT < 23) return;
            try {
                Display display = getWindowManager().getDefaultDisplay();
                Display.Mode current = display.getMode();
                Display.Mode best = null;
                for (Display.Mode m : display.getSupportedModes()) {
                    if (m.getPhysicalWidth() != current.getPhysicalWidth()
                            || m.getPhysicalHeight() != current.getPhysicalHeight()) continue;
                    float r = m.getRefreshRate();
                    boolean fits = false;
                    for (int k = 1; k <= 3; k++) if (Math.abs(r - fps * k) < 0.02f) fits = true;
                    if (fits && (best == null || r > best.getRefreshRate())) best = m;
                }
                if (best == null || best.getModeId() == current.getModeId()) return;
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                if (lp.preferredDisplayModeId == best.getModeId()) return;
                lp.preferredDisplayModeId = best.getModeId();
                getWindow().setAttributes(lp);
            } catch (Throwable ignored) {
            }
        }
    };

    // ------------------------------------------------------------------ Alexa and system media controls

    private void setupSession() {
        try {
            session = new MediaSession(this, "DiscShelf");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    if (player != null && !player.isPlaying()) player.play();
                }

                @Override
                public void onPause() {
                    if (player != null && player.isPlaying()) player.pause();
                }

                @Override
                public void onStop() {
                    finish();
                }

                @Override
                public void onSeekTo(long pos) {
                    if (player != null && !inMenu) player.setTime(Math.max(0, pos));
                }

                @Override
                public void onFastForward() {
                    seekBy(30000);
                }

                @Override
                public void onRewind() {
                    seekBy(-10000);
                }

                @Override
                public void onSkipToNext() {
                    skipChapter(1);
                }

                @Override
                public void onSkipToPrevious() {
                    skipChapter(-1);
                }
            });
        } catch (Throwable t) {
            session = null;
        }
    }

    private void updateSession() {
        if (session == null) return;
        try {
            boolean playing = player != null && player.isPlaying();
            long pos = player != null ? Math.max(0, player.getTime()) : 0;
            long len = player != null ? player.getLength() : -1;
            if (len != sessionDuration) {
                sessionDuration = len;
                MediaMetadata.Builder meta = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, disc.title);
                if (len > 0) meta.putLong(MediaMetadata.METADATA_KEY_DURATION, len);
                session.setMetadata(meta.build());
            }
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD
                    | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f)
                    .build());
        } catch (Throwable ignored) {
        }
    }
}
