package com.miniide.app.editor

import android.content.Context
import android.view.KeyEvent

/**
 * Fiziksel klavye ve ekran üstü PC tuş çubuğu için birleşik kısayol katmanı:
 * - KeyAction: VS Code uyumlu tüm editör komutları
 * - StickyModifierManager: Ctrl / Alt / Shift için tek dokunuş (ONE_SHOT) ve çift dokunuş (LOCKED) durum makinesi
 * - KeybindingRegistry: Kısayol çözümleme, özelleştirme ve çakışma tespiti
 */
enum class KeyAction(val titleTr: String, val defaultStroke: KeyStroke) {
    SAVE("Kaydet", KeyStroke(LogicalKey.S, ctrl = true)),
    UNDO("Geri Al", KeyStroke(LogicalKey.Z, ctrl = true)),
    REDO("Yinele", KeyStroke(LogicalKey.Y, ctrl = true)),
    FIND("Ara", KeyStroke(LogicalKey.F, ctrl = true)),
    REPLACE("Ara ve Değiştir", KeyStroke(LogicalKey.H, ctrl = true)),
    PROJECT_SEARCH("Projede Ara (Ctrl+Shift+F)", KeyStroke(LogicalKey.F, ctrl = true, shift = true)),
    GOTO_LINE("Satıra Git", KeyStroke(LogicalKey.G, ctrl = true)),
    QUICK_OPEN("Hızlı Dosya Aç", KeyStroke(LogicalKey.P, ctrl = true)),
    COMMAND_PALETTE("Komut Paleti", KeyStroke(LogicalKey.P, ctrl = true, shift = true)),
    SELECT_NEXT_MATCH("Sonraki Eşleşmeyi Seç", KeyStroke(LogicalKey.D, ctrl = true)),
    TOGGLE_COMMENT("Yorum Satırı Aç/Kapat", KeyStroke(LogicalKey.SLASH, ctrl = true)),
    INDENT_LINE("Girintiyi Artır", KeyStroke(LogicalKey.RIGHT_BRACKET, ctrl = true)),
    OUTDENT_LINE("Girintiyi Azalt", KeyStroke(LogicalKey.LEFT_BRACKET, ctrl = true)),
    MOVE_LINE_UP("Satırı Yukarı Taşı", KeyStroke(LogicalKey.UP, alt = true)),
    MOVE_LINE_DOWN("Satırı Aşağı Taşı", KeyStroke(LogicalKey.DOWN, alt = true)),
    DUPLICATE_LINE_UP("Satırı Yukarı Çoğalt", KeyStroke(LogicalKey.UP, alt = true, shift = true)),
    DUPLICATE_LINE_DOWN("Satırı Aşağı Çoğalt", KeyStroke(LogicalKey.DOWN, alt = true, shift = true)),
    DELETE_LINE("Satırı Sil", KeyStroke(LogicalKey.K, ctrl = true, shift = true)),
    WORD_LEFT("Önceki Kelimeye Atla", KeyStroke(LogicalKey.LEFT, ctrl = true)),
    WORD_RIGHT("Sonraki Kelimeye Atla", KeyStroke(LogicalKey.RIGHT, ctrl = true)),
    WORD_LEFT_SELECT("Önceki Kelimeyi Seçerek Atla", KeyStroke(LogicalKey.LEFT, ctrl = true, shift = true)),
    WORD_RIGHT_SELECT("Sonraki Kelimeyi Seçerek Atla", KeyStroke(LogicalKey.RIGHT, ctrl = true, shift = true)),
    SELECT_ALL("Tümünü Seç", KeyStroke(LogicalKey.A, ctrl = true)),
    COPY("Kopyala", KeyStroke(LogicalKey.C, ctrl = true)),
    CUT("Kes", KeyStroke(LogicalKey.X, ctrl = true)),
    PASTE("Yapıştır", KeyStroke(LogicalKey.V, ctrl = true)),
    TOGGLE_SIDE_PANEL("Dosya Panelini Aç/Kapat", KeyStroke(LogicalKey.B, ctrl = true)),
    SWITCH_TAB("Dosya / Sekme Değiştir", KeyStroke(LogicalKey.TAB, ctrl = true)),
    CLOSE_TAB("Dosyayı / Sekmeyi Kapat", KeyStroke(LogicalKey.W, ctrl = true)),
    BUILD_AND_RUN("Derle ve Çalıştır", KeyStroke(LogicalKey.F5))
}

enum class LogicalKey(val label: String) {
    ESC("Esc"),
    TAB("Tab"),
    LEFT("←"),
    UP("↑"),
    DOWN("↓"),
    RIGHT("→"),
    HOME("Home"),
    END("End"),
    PGUP("PgUp"),
    PGDN("PgDn"),
    DEL("Del"),
    A("A"), B("B"), C("C"), D("D"), E("E"), F("F"), G("G"), H("H"), I("I"),
    J("J"), K("K"), L("L"), M("M"), N("N"), O("O"), P("P"), Q("Q"), R("R"),
    S("S"), T("T"), U("U"), V("V"), W("W"), X("X"), Y("Y"), Z("Z"),
    SLASH("/"),
    LEFT_BRACKET("["),
    RIGHT_BRACKET("]"),
    F1("F1"), F2("F2"), F3("F3"), F4("F4"), F5("F5"), F6("F6"),
    F7("F7"), F8("F8"), F9("F9"), F10("F10"), F11("F11"), F12("F12");

    companion object {
        fun fromAndroidKeyCode(keyCode: Int): LogicalKey? = when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> ESC
            KeyEvent.KEYCODE_TAB -> TAB
            KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            KeyEvent.KEYCODE_DPAD_UP -> UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> HOME
            KeyEvent.KEYCODE_MOVE_END -> END
            KeyEvent.KEYCODE_PAGE_UP -> PGUP
            KeyEvent.KEYCODE_PAGE_DOWN -> PGDN
            KeyEvent.KEYCODE_FORWARD_DEL -> DEL
            KeyEvent.KEYCODE_A -> A
            KeyEvent.KEYCODE_B -> B
            KeyEvent.KEYCODE_C -> C
            KeyEvent.KEYCODE_D -> D
            KeyEvent.KEYCODE_E -> E
            KeyEvent.KEYCODE_F -> F
            KeyEvent.KEYCODE_G -> G
            KeyEvent.KEYCODE_H -> H
            KeyEvent.KEYCODE_I -> I
            KeyEvent.KEYCODE_J -> J
            KeyEvent.KEYCODE_K -> K
            KeyEvent.KEYCODE_L -> L
            KeyEvent.KEYCODE_M -> M
            KeyEvent.KEYCODE_N -> N
            KeyEvent.KEYCODE_O -> O
            KeyEvent.KEYCODE_P -> P
            KeyEvent.KEYCODE_Q -> Q
            KeyEvent.KEYCODE_R -> R
            KeyEvent.KEYCODE_S -> S
            KeyEvent.KEYCODE_T -> T
            KeyEvent.KEYCODE_U -> U
            KeyEvent.KEYCODE_V -> V
            KeyEvent.KEYCODE_W -> W
            KeyEvent.KEYCODE_X -> X
            KeyEvent.KEYCODE_Y -> Y
            KeyEvent.KEYCODE_Z -> Z
            KeyEvent.KEYCODE_SLASH -> SLASH
            KeyEvent.KEYCODE_LEFT_BRACKET -> LEFT_BRACKET
            KeyEvent.KEYCODE_RIGHT_BRACKET -> RIGHT_BRACKET
            KeyEvent.KEYCODE_F1 -> F1
            KeyEvent.KEYCODE_F2 -> F2
            KeyEvent.KEYCODE_F3 -> F3
            KeyEvent.KEYCODE_F4 -> F4
            KeyEvent.KEYCODE_F5 -> F5
            KeyEvent.KEYCODE_F6 -> F6
            KeyEvent.KEYCODE_F7 -> F7
            KeyEvent.KEYCODE_F8 -> F8
            KeyEvent.KEYCODE_F9 -> F9
            KeyEvent.KEYCODE_F10 -> F10
            KeyEvent.KEYCODE_F11 -> F11
            KeyEvent.KEYCODE_F12 -> F12
            else -> null
        }
    }
}

data class KeyStroke(
    val key: LogicalKey,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false
) {
    fun format(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        append(key.label)
    }

    fun serialize(): String = "${if (ctrl) 1 else 0}:${if (alt) 1 else 0}:${if (shift) 1 else 0}:${key.name}"

    companion object {
        fun deserialize(raw: String): KeyStroke? {
            val parts = raw.split(':')
            if (parts.size != 4) return null
            val key = try { LogicalKey.valueOf(parts[3]) } catch (_: Exception) { return null }
            return KeyStroke(
                key = key,
                ctrl = parts[0] == "1",
                alt = parts[1] == "1",
                shift = parts[2] == "1"
            )
        }

        /** Kullanıcı metninden (ör. "Ctrl+Shift+K" veya "Alt+↑") KeyStroke ayrıştırır. */
        fun parse(text: String): KeyStroke? {
            val tokens = text.trim().split('+').map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            var ctrl = false
            var alt = false
            var shift = false
            var targetKey: LogicalKey? = null

            for (tok in tokens) {
                when (tok.lowercase()) {
                    "ctrl", "control" -> ctrl = true
                    "alt" -> alt = true
                    "shift" -> shift = true
                    "up", "↑" -> targetKey = LogicalKey.UP
                    "down", "↓" -> targetKey = LogicalKey.DOWN
                    "left", "←" -> targetKey = LogicalKey.LEFT
                    "right", "→" -> targetKey = LogicalKey.RIGHT
                    else -> {
                        targetKey = LogicalKey.entries.firstOrNull {
                            it.label.equals(tok, ignoreCase = true) || it.name.equals(tok, ignoreCase = true)
                        }
                    }
                }
            }
            val k = targetKey ?: return null
            return KeyStroke(k, ctrl = ctrl, alt = alt, shift = shift)
        }
    }
}

/**
 * Yapışkan (sticky) değiştirici tuş durumları:
 * - OFF: pasif
 * - ONE_SHOT: bir kez dokunuldu, yalnızca bir sonraki tuşa uygulanıp OFF'a döner
 * - LOCKED: iki kez dokunuldu, kilitli kalır, tekrar dokunulunca OFF'a döner
 */
enum class StickyState {
    OFF,
    ONE_SHOT,
    LOCKED;

    val isActive: Boolean
        get() = this != OFF
}

enum class ModifierKey { CTRL, ALT, SHIFT }

class StickyModifierManager {
    var ctrl: StickyState = StickyState.OFF
        private set
    var alt: StickyState = StickyState.OFF
        private set
    var shift: StickyState = StickyState.OFF
        private set

    /**
     * Değiştirici tuşa dokunulduğunda durumu ilerletir:
     * OFF -> ONE_SHOT -> LOCKED -> OFF
     */
    fun onModifierTapped(modifier: ModifierKey): StickyState {
        val next = when (getState(modifier)) {
            StickyState.OFF -> StickyState.ONE_SHOT
            StickyState.ONE_SHOT -> StickyState.LOCKED
            StickyState.LOCKED -> StickyState.OFF
        }
        setState(modifier, next)
        return next
    }

    fun getState(modifier: ModifierKey): StickyState = when (modifier) {
        ModifierKey.CTRL -> ctrl
        ModifierKey.ALT -> alt
        ModifierKey.SHIFT -> shift
    }

    private fun setState(modifier: ModifierKey, state: StickyState) {
        when (modifier) {
            ModifierKey.CTRL -> ctrl = state
            ModifierKey.ALT -> alt = state
            ModifierKey.SHIFT -> shift = state
        }
    }

    /**
     * Bir normal tuşa basıldığında aktif Ctrl/Alt/Shift durumlarını KeyStroke'a uygular
     * ve ONE_SHOT olanları otomatik olarak OFF'a düşürür (LOCKED olanlar korunur).
     */
    fun consumeForKey(
        key: LogicalKey,
        hwCtrl: Boolean = false,
        hwAlt: Boolean = false,
        hwShift: Boolean = false
    ): KeyStroke {
        val effectiveCtrl = hwCtrl || ctrl.isActive
        val effectiveAlt = hwAlt || alt.isActive
        val effectiveShift = hwShift || shift.isActive

        if (ctrl == StickyState.ONE_SHOT) ctrl = StickyState.OFF
        if (alt == StickyState.ONE_SHOT) alt = StickyState.OFF
        if (shift == StickyState.ONE_SHOT) shift = StickyState.OFF

        return KeyStroke(
            key = key,
            ctrl = effectiveCtrl,
            alt = effectiveAlt,
            shift = effectiveShift
        )
    }

    fun clearAll() {
        ctrl = StickyState.OFF
        alt = StickyState.OFF
        shift = StickyState.OFF
    }
}

/**
 * VS Code uyumlu kısayol kayıt defteri.
 * Kullanıcı özelleştirmelerini destekler ve çakışmaları (aynı KeyStroke'a bağlı birden fazla komut) tespit eder.
 */
class KeybindingRegistry {

    private val actionToStroke = LinkedHashMap<KeyAction, KeyStroke>()

    init {
        resetToDefaults()
    }

    fun resetToDefaults() {
        actionToStroke.clear()
        for (action in KeyAction.entries) {
            actionToStroke[action] = action.defaultStroke
        }
    }

    fun getStroke(action: KeyAction): KeyStroke = actionToStroke[action] ?: action.defaultStroke

    fun setStroke(action: KeyAction, stroke: KeyStroke) {
        actionToStroke[action] = stroke
    }

    fun allBindings(): Map<KeyAction, KeyStroke> = LinkedHashMap(actionToStroke)

    /** Verilen KeyStroke'a karşılık gelen KeyAction'ı çözümler. */
    fun resolve(stroke: KeyStroke): KeyAction? {
        for ((action, boundStroke) in actionToStroke) {
            if (boundStroke == stroke) return action
        }
        // Ek yerleşik ikincil kısayollar (Ctrl+Shift+Z -> REDO, F1 -> COMMAND_PALETTE)
        if (stroke == KeyStroke(LogicalKey.Z, ctrl = true, shift = true)) return KeyAction.REDO
        if (stroke == KeyStroke(LogicalKey.F1)) return KeyAction.COMMAND_PALETTE
        return null
    }

    /** Belirli bir aksiyona yeni bir kısayol atanırken çakışan diğer aksiyonları döndürür. */
    fun findConflicts(targetAction: KeyAction, candidateStroke: KeyStroke): List<KeyAction> {
        return actionToStroke.filter { (action, stroke) ->
            action != targetAction && stroke == candidateStroke
        }.keys.toList()
    }

    /** Tüm kayıtlı kısayollar içindeki çakışma gruplarını (KeyStroke -> KeyAction listesi) döndürür. */
    fun findAllConflicts(): Map<KeyStroke, List<KeyAction>> {
        return actionToStroke.entries
            .groupBy({ it.value }, { it.key })
            .filterValues { it.size > 1 }
    }

    fun saveToPrefs(context: Context) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        for ((action, stroke) in actionToStroke) {
            editor.putString(action.name, stroke.serialize())
        }
        editor.apply()
    }

    fun loadFromPrefs(context: Context) {
        resetToDefaults()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for (action in KeyAction.entries) {
            val raw = prefs.getString(action.name, null) ?: continue
            val parsed = KeyStroke.deserialize(raw) ?: continue
            actionToStroke[action] = parsed
        }
    }

    companion object {
        private const val PREFS_NAME = "ide_keybindings"
    }
}
