package com.github.stjiege.ftpshare.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.navigation.NavigationBarView;

import java.util.ArrayList;
import java.util.List;

/**
 * 底部导航的「选中项图标放大 + 文字淡入淡出」动效。
 *
 * <p>这是建立在 material 1.11.0 实际实现之上的做法，三条前提都已用 javap 核对过
 * （结论另见 .workbuddy/memory/MEMORY.md 第四节）：
 * <ol>
 *   <li>{@link NavigationBarView#getMenuView()} 是 public，可干净拿到 MenuView，
 *       其子 View 就是每个 item（NavigationBarItemView），不必反射；</li>
 *   <li>item 内部层次固定：child0 = 图标容器（FrameLayout，含图标 ImageView 与 M3 指示器 View），
 *       child1 = 标签组（BaselineLayout，含 small / large 两个 TextView）。这些 id 在库里
 *       不是 public 资源，编译期拿不到，所以运行时先用 getIdentifier 按名字找、失败再按结构找；</li>
 *   <li><b>关键</b>：Material 切换选中时走
 *       {@code setViewScaleValues(view, scaleX, scaleY, visibility)} —— 只写 scale 与 visibility，
 *       <b>从不写 alpha</b>。所以 alpha 是块无主地：我们驱动它做淡入淡出，Material 不会覆盖。</li>
 * </ol>
 *
 * <p>两个必须处理的坑：
 * <ul>
 *   <li>放大的图标会溢出图标容器，而 FrameLayout 默认裁剪，必须把 clipChildren 关掉；</li>
 *   <li>label 的 visibility 每次切换都会被 Material 重置，因此动画要 post 到下一帧再施加，
 *       等于排在 Material 的更新之后。</li>
 * </ul>
 *
 * <p>用法：{@code NavItemMotion.attach(navigation)} 建好，切页时调 {@link #select(int, boolean)}。
 * 本类不做描边/实心一类的图标形变，只做缩放位移与文字透明度。
 */
public final class NavItemMotion {

    /** 选中项图标的放大倍数：24dp 图标放大到约 32dp，正好压到文字区顶部。 */
    private static final float ICON_SCALE = 1.34f;
    /**
     * 选中项图标的下移量（dp）：把它从 Material 摆的位置拉回 item 的几何中心。
     *
     * <p>这个数是算出来的，不是试出来的。material 1.11.0 的
     * {@code NavigationBarItemView.setChecked()} 在 LABELED 模式（本工程 3 个 item，
     * 走 {@code isShifting == false} 分支）下，对选中项执行
     * {@code setViewTopMarginAndGravity(iconContainer, (int)(itemPaddingTop + shiftAmount), TOP)}，
     * 其中：
     * <ul>
     *   <li>{@code itemPaddingTop = design_bottom_navigation_margin = 8dp}（构造函数里读的）；</li>
     *   <li>{@code shiftAmount = smallLabelTextSize - largeLabelTextSize = 12sp - 14sp = -2dp}
     *       （来自 {@code calculateTextScaleFactors}：{@code shiftAmount = f1 - f2}）。</li>
     * </ul>
     * 所以选中态图标容器 top = 8 - 2 = 6dp，图标高 24dp，中心落在 6 + 12 = <b>18dp</b>；
     * 而 item 高 56dp、中心在 <b>28dp</b> —— Material 自己就把它摆偏上了 10dp。
     * 取 10dp 正好把图标中心拉回 item 中心（scale 以中心为基准，放大不改变中心位置）。
     *
     * <p>字体缩放（系统"显示大小"）会同步放大 12sp/14sp 的差值，这个固定值会跟着产生
     * 零点几 dp 的误差，肉眼不可见；真要严格跟随就得在布局完成后按实际 textSize 现算。
     */
    private static final float ICON_DROP_DP = 10f;
    /** 一次切换的时长，与 Material 自身的动效节奏对齐。 */
    private static final long DURATION_MS = 220L;

    private static final String ID_ICON = "navigation_bar_item_icon_view";
    private static final String ID_SMALL_LABEL = "navigation_bar_item_small_label_view";
    private static final String ID_LARGE_LABEL = "navigation_bar_item_large_label_view";

    private final NavigationBarView navigationBarView;
    private final List<Item> items = new ArrayList<>();
    private final float dropPx;

    private ValueAnimator animator;
    private Runnable pending;
    private int selected = -1;

    /** 一个导航格子里我们真正要驱动的两个东西，外加它当前的"选中度"。 */
    private static final class Item {
        ImageView icon;
        TextView[] labels = new TextView[0];
        float progress;
        float from;
        float to;
    }

    private NavItemMotion(NavigationBarView view) {
        this.navigationBarView = view;
        this.dropPx = ICON_DROP_DP * view.getResources().getDisplayMetrics().density;
    }

    /**
     * 绑定一个底部导航栏。item 还没填充时会自动补一次重试，调用方不用管时序。
     */
    public static NavItemMotion attach(NavigationBarView view) {
        NavItemMotion motion = new NavItemMotion(view);
        motion.rebuild();
        if (motion.items.isEmpty()) {
            view.post(motion::rebuild);
        }
        return motion;
    }

    /**
     * 切换选中项。
     *
     * @param animate false 时不做过渡、直接落到终态（首次进入页面、屏幕重建时用）。
     */
    public void select(int index, boolean animate) {
        // 带动画时重复选同一项没必要重播；不带动画时必须重新落位，否则界面可能停在半路。
        if (index == selected && animate) return;
        selected = index;
        if (pending != null) {
            navigationBarView.removeCallbacks(pending);
        }
        pending = () -> applyTarget(index, animate);
        // post 到下一帧：保证排在 Material 自己的 setChecked 之后，否则刚设好的状态会被它冲掉。
        navigationBarView.post(pending);
    }

    /** Activity 销毁时调用，避免动画握着已经废掉的 View。 */
    public void release() {
        if (pending != null) {
            navigationBarView.removeCallbacks(pending);
            pending = null;
        }
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    /**
     * NavigationBarView.getMenuView() 是 material 的受限 API（@RestrictTo LIBRARY_GROUP）。
     * 底部导航选中项要放大图标并移动，必须拿到内部 MenuView 才能遍历其中的 ImageView/TextView，
     * 没有公开的替代入口，故在此显式标注放行。库升级时要重新确认这个方法还在。
     */
    @SuppressLint("RestrictedApi")
    private void rebuild() {
        items.clear();
        // MenuView 是个接口，与 View 没有继承关系，只能先按 Object 收下再判实际类型。
        Object menuView = navigationBarView.getMenuView();
        if (!(menuView instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) menuView;
        // 放大后的图标要能溢出自己的容器，这条必须自上而下放松，否则会被裁掉下半截。
        unclipTree(group);
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            Item item = new Item();
            item.icon = findIcon(child);
            item.labels = findLabels(child);
            items.add(item);
        }
    }

    private void applyTarget(int index, boolean animate) {
        if (items.isEmpty()) rebuild();
        if (items.isEmpty()) return;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            item.from = item.progress;
            item.to = (i == index) ? 1f : 0f;
        }
        if (!animate || !navigationBarView.isLaidOut()) {
            for (Item item : items) {
                item.progress = item.to;
                apply(item);
            }
            return;
        }
        ValueAnimator animation = ValueAnimator.ofFloat(0f, 1f);
        animation.setDuration(DURATION_MS);
        animation.setInterpolator(new FastOutSlowInInterpolator());
        animation.addUpdateListener(valueAnimator -> {
            float fraction = (float) valueAnimator.getAnimatedValue();
            for (Item item : items) {
                item.progress = item.from + (item.to - item.from) * fraction;
                apply(item);
            }
        });
        animator = animation;
        animation.start();
    }

    /**
     * 把一个 item 施加到给定的"选中度"。文字同时写 small 与 large 两个标签的 alpha，
     * 因为 Material 会在两者之间硬切可见性，只写其中一个会让状态在切换瞬间对不上。
     */
    private void apply(Item item) {
        float progress = item.progress;
        if (item.icon != null) {
            float scale = 1f + (ICON_SCALE - 1f) * progress;
            item.icon.setScaleX(scale);
            item.icon.setScaleY(scale);
            item.icon.setTranslationY(dropPx * progress);
        }
        float alpha = 1f - progress;
        for (TextView label : item.labels) {
            if (label != null) label.setAlpha(alpha);
        }
    }

    /** 裁剪是 ViewGroup 的属性（View 没有），所以这里只对容器递归。 */
    private static void unclipTree(ViewGroup group) {
        group.setClipChildren(false);
        group.setClipToPadding(false);
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                unclipTree((ViewGroup) child);
            }
        }
    }

    private static ImageView findIcon(View item) {
        ImageView byName = findByName(item, ID_ICON, ImageView.class);
        if (byName != null) return byName;
        // 结构兜底：item 里唯一的 ImageView 就是图标（另一个子 View 是 M3 指示器，类型是 View）。
        return firstDescendant(item, ImageView.class);
    }

    private static TextView[] findLabels(View item) {
        TextView small = findByName(item, ID_SMALL_LABEL, TextView.class);
        TextView large = findByName(item, ID_LARGE_LABEL, TextView.class);
        if (small != null && large != null) return new TextView[] {small, large};
        // 结构兜底：唯一一个"头两个子 View 都是 TextView"的容器就是标签组（顺序即 small / large）。
        if (item instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) item;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (!(child instanceof ViewGroup)) continue;
                ViewGroup candidate = (ViewGroup) child;
                if (candidate.getChildCount() >= 2
                        && candidate.getChildAt(0) instanceof TextView
                        && candidate.getChildAt(1) instanceof TextView) {
                    return new TextView[] {(TextView) candidate.getChildAt(0),
                            (TextView) candidate.getChildAt(1)};
                }
            }
        }
        return new TextView[0];
    }

    /**
     * 按资源名查找。这些 id 在 material 库里不是 public，编译期写不出来，
     * 但合并进 APK 后确实存在于本应用包内，运行时按名字取是合法的。
     */
    private static <T extends View> T findByName(View root, String name, Class<T> type) {
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        if (id == 0) return null;
        View found = root.findViewById(id);
        return type.isInstance(found) ? type.cast(found) : null;
    }

    private static <T extends View> T firstDescendant(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = firstDescendant(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
