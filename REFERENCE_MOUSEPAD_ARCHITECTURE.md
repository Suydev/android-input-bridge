# Reference APK — Mouse Pad Architecture (btmouse-jadx)

## Core Touch Handler: tx2.java

### Touch Input Processing
- Implements `View.OnTouchListener` + `View.OnHoverListener`
- Two `y` (velocity tracker) objects for X/Y axes — friction-based with sub-pixel accumulation
- `pd0(-1.0, 1.0)` range constraint for normalized pointer position
- Speed scaling via `ot5.g(settingValue, 3.0f)` — maps 0-100 to float multiplier

### Touch Events
- `onHover()` — ACTION_HOVER_MOVE(7): computes delta via `y.f()`, calls `sx2.m(dx, dy)`
- `onHover()` — ACTION_HOVER_ENTER(9): records initial position via `y.p()`
- `onTouch()` — handles single-finger move, two-finger scroll, tap, long press

### Button Mapping
- `getActionButton()` → byte flags: 1=left, 2=middle, 4=back, 8=right, 16=extra

### Speed Configuration
- `f(pointerSpeed, scrollSpeed)` — called from settings
- `r = ot5.g(pointerSpeed, 3.0f)` — pointer sensitivity
- `x = ot5.g(scrollSpeed, 5.0f) / 24.0f` — scroll sensitivity

## Mouse Callback Interface: sx2.java

```java
void m(int dx, int dy)        // cursor move
void t(int scroll, int pan)   // scroll wheel
void k(byte btn, boolean down)// button press/release
void y(byte btn, boolean delay)// button click with animation
void a()                       // record timestamp
void l()                       // request pointer capture
void p(boolean z)              // update capture state
```

## Implementation: cw4.java

### Mouse Move
```java
m(int dx, int dy) → ie1.v(new ee1(dx, dy, 1))  // type 1 = MouseMove
```

### Scroll
```java
t(int scroll, int pan) → ie1.v(new ee1(scroll, pan, 0))  // type 0 = Scroll
// Inverts if settings.invertScrolling is true
```

### Button Press/Release
```java
k(byte button, boolean isDown) → ie1.v(new fe1(button, 0, isDown))
```

### Button Click (with haptic delay)
```java
y(byte button, boolean withDelay) → ie1.v(new fe1(button, 1, withDelay))
```

## Pointer Trail: PointerPathView.java

### Trail Storage
- `LinkedList<oi3> L` — trail points (TimedPoint: x, y, timestamp, isStart)
- Max age: 2000ms (field `H`)
- Check interval: 50ms (field `I`)

### Drawing
- Trail lines: STROKE paint, alpha fades with age, stroke width starts at 4dp * density
- Cursor dot: FILL paint, white with black outline
- Tap ripple: expanding circle animation via ValueAnimator

### Methods
- `e(x, y, isStart)` — add trail point + invalidate
- `l(x, y)` — trigger tap ripple animation
- `onDraw(Canvas)` — iterate trail, compute age-based alpha/stroke-width, draw lines + circle

## Layout: control_item_touchpad.xml

### Structure
```
LinearLayout (vertical)
├── mouse_buttons_top (include, 72dp, GONE by default)
├── ConstraintLayout (weight=1, fills space)
│   ├── PointerPathView (@+id/touch) — main touch surface
│   ├── scrollbar_left (Group: up arrow + bar + down arrow)
│   └── scrollbar_right (Group: up arrow + bar + down arrow)
├── mouse_buttons_bottom (include, 72dp, GONE by default)
├── airmouse_touch (56dp, GONE)
└── disable_capture_button (GONE)
```

### Mouse Buttons: mouse_buttons.xml
- 3 MaterialButtons in horizontal LinearLayout: left, middle, right
- Equal weight, gone by default

## Settings (t74.java)

| Setting Key | Type | Description |
|-------------|------|-------------|
| `touch_click_enabled` | Boolean | Tap-to-click |
| `show_mouse_buttons` | String | top/bottom |
| `visible_mouse_buttons` | Set | left/middle/right |
| `mouse_pointer_speed` | Int (0-100) | Pointer speed |
| `show_scroll_bar` | String | left/right |
| `mouse_scroll_speed` | Int | Scroll speed |
| `mouse_invert_scroll` | Boolean | Invert direction |
| `pen_drawing_mode` | Boolean | Pen mode |
| `mouse_jiggle_mode` | String | Off/screen/always |

## Event Pipeline
```
Touch → tx2.onTouch() → y.f() delta → sx2.m(dx,dy)
    → cw4.m() → ee1(dx,dy,1) → ie1.v() queue
    → a10.s(dx,dy) → BLE HID sendReport()
```
