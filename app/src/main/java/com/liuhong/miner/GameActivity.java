package com.liuhong.miner;

import android.app.Activity;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * 黄金矿工 TV 版。
 * 流程：主菜单（开始游戏 / 语音开关，显示最高分）→ 矿洞对局（自绘视图）
 * → 结算界面（本局得分 / 到达关卡 / 最高分，破纪录提示）。
 * 支持中文语音播报（抓到什么、过关、游戏结束）。
 */
public class GameActivity extends Activity implements MinerView.Callback {

    private static final String PREFS = "miner_stats";
    private static final String KEY_HIGH = "high";

    private SharedPreferences prefs;
    private int highScore;

    private MinerView minerView;
    private boolean voiceOn = true;

    // 结算界面缓存
    private int lastTotal;
    private int lastLevel;

    private ToneGenerator tones;
    private TextToSpeech tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        highScore = prefs.getInt(KEY_HIGH, 0);
        voiceOn = prefs.getBoolean("voice", true);

        tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
        initTts();

        showMenu();
    }

    // ---------- 语音 ----------

    private void initTts() {
        try {
            tts = new TextToSpeech(this, status -> {
                if (status != TextToSpeech.SUCCESS) {
                    tts = null;
                } else {
                    int r = tts.setLanguage(Locale.CHINESE);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts.shutdown();
                        tts = null;
                    } else {
                        tts.setSpeechRate(0.95f);
                    }
                }
                runOnUiThread(this::updateVoiceButton);
            });
        } catch (Exception e) {
            tts = null;
        }
    }

    /** 语音播报（静默降级：无 TTS 或关闭时不发声）。 */
    private void speak(String text) {
        if (!voiceOn || tts == null) return;
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "miner");
        } catch (Exception ignored) {
        }
    }

    // ---------- 主菜单 ----------

    private void showMenu() {
        setContentView(R.layout.activity_menu);

        TextView tvHigh = findViewById(R.id.tv_high);
        tvHigh.setText("最高分：" + highScore);

        Button btnStart = findViewById(R.id.btn_menu_start);
        Button btnVoice = findViewById(R.id.btn_menu_voice);

        btnStart.setOnClickListener(v -> startGame());
        btnVoice.setOnClickListener(v -> {
            if (tts == null) {
                toast("电视没有安装语音引擎，无法使用语音");
                return;
            }
            voiceOn = !voiceOn;
            prefs.edit().putBoolean("voice", voiceOn).apply();
            updateVoiceButton();
            if (voiceOn) speak("语音已开启");
        });

        btnStart.requestFocus();
        updateVoiceButton();
    }

    private void updateVoiceButton() {
        Button btnVoice = findViewById(R.id.btn_menu_voice);
        if (btnVoice == null) return;
        btnVoice.setText(tts == null ? "语音：不可用" : (voiceOn ? "语音：开" : "语音：关"));
    }

    // ---------- 对局 ----------

    private void startGame() {
        setContentView(R.layout.activity_game);
        minerView = findViewById(R.id.miner);
        minerView.setCallback(this);
        minerView.startGame();
    }

    @Override
    public void onGrab(String speakText, boolean isBonus) {
        if (isBonus) {
            // 问号袋奖励：欢快双音
            try {
                tones.startTone(ToneGenerator.TONE_PROP_ACK, 80);
            } catch (Exception ignored) {
            }
        } else {
            beep(false);
        }
        speak(speakText);
    }

    @Override
    public void onLevelClear(int level, int levelScore) {
        beep(true);
        speak("第" + level + "关完成！本关得分" + levelScore + "分");
    }

    @Override
    public void onGameOver(int totalScore, int level) {
        lastTotal = totalScore;
        lastLevel = level;
        beep(true);
        speak("游戏结束！总分" + totalScore + "分，到达第" + level + "关");
        // 与 MinerView 的结算延迟对齐，让玩家看清最后一钩
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> {
                    if (!isFinishing()) showResult();
                }, 1600);
    }

    // ---------- 结算界面 ----------

    private void showResult() {
        setContentView(R.layout.activity_result);

        TextView tvResult = findViewById(R.id.tv_result);
        TextView tvStats = findViewById(R.id.tv_result_stats);

        boolean newRecord = lastTotal > highScore;
        if (newRecord) {
            highScore = lastTotal;
            prefs.edit().putInt(KEY_HIGH, highScore).apply();
        }

        tvResult.setText("游戏结束");
        StringBuilder sb = new StringBuilder();
        sb.append("本局总分：").append(lastTotal).append(" 分\n");
        sb.append("到达关卡：第 ").append(lastLevel).append(" 关\n");
        sb.append("历史最高：").append(highScore).append(" 分");
        if (newRecord) {
            sb.append("\n新纪录！");
        }
        tvStats.setText(sb.toString());

        Button btnAgain = findViewById(R.id.btn_again);
        Button btnToMenu = findViewById(R.id.btn_tomenu);
        btnAgain.setOnClickListener(v -> startGame());
        btnToMenu.setOnClickListener(v -> showMenu());
        btnAgain.requestFocus();
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onPause() {
        super.onPause();
        if (minerView != null) minerView.pauseGame();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (minerView != null) minerView.resumeGame();
    }

    private void beep(boolean strong) {
        try {
            tones.startTone(strong
                    ? ToneGenerator.TONE_PROP_ACK
                    : ToneGenerator.TONE_PROP_BEEP, 70);
        } catch (Exception ignored) {
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (tones != null) tones.release();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }
}
