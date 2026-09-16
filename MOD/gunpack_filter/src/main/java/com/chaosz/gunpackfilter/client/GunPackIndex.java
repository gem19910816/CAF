package com.chaosz.gunpackfilter.client;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 扫描 TaCZ 的枪包目录（&lt;游戏目录&gt;/tacz/），建立「枪包文件夹 ↔ 资源命名空间」的对应表。
 *
 * <p>TaCZ 运行时只认资源命名空间，一个枪包文件夹里可以塞多个命名空间
 * （例如 [TaCZ]Warzone 里就有 bo6 / bo7 / jak / nmw1 / nmw2 / nmw3 / wz）。
 * 这里按 TaCZ 自己的扫描方式（一层目录 + .zip）把文件夹还原出来，
 * 这样筛选按钮才能做到「一个按钮 = 一个枪包」。</p>
 *
 * <p><b>命名空间归属唯一</b>：两个枪包可能声明同一个命名空间（用户整合包里
 * [TaCZ]Warzone 和 [LRTactical]Warzone Tactical 就共用 nmw2/nmw3/wz，
 * GunpowderRevolution 和 tacz_default_gun 也共用 tacz）。这种时候按「该命名空间下谁的文件多」
 * 判给唯一一个包，否则会出现好几个按钮点出来内容一样、根本分不清。</p>
 *
 * <p>被抢走全部命名空间的枪包仍然保留在列表里（按钮还在，只是点出来是空页签），
 * 这样用户能看出来「这个包装了但被覆盖了」。</p>
 *
 * <p>这个类刻意不依赖任何 Minecraft / Forge 类型，方便单独测试。</p>
 */
public final class GunPackIndex {

    private static final String[] RESOURCE_ROOTS = {"assets", "data"};
    /** 单个命名空间单个包最多数这么多文件，够比大小就行，避免极端情况下卡住。 */
    private static final long FILE_COUNT_CAP = 5000L;

    /** 枪包名 → 该包独占的命名空间（可能为空，表示被别的包覆盖光了） */
    private static Map<String, Set<String>> packToNamespaces;
    /** 命名空间 → 独占它的枪包 */
    private static Map<String, String> namespaceToPack;

    private GunPackIndex() {
    }

    public static boolean isLoaded() {
        return packToNamespaces != null;
    }

    public static void invalidate() {
        packToNamespaces = null;
        namespaceToPack = null;
    }

    /** 扫描枪包根目录。重复调用会重新扫描。 */
    public static void load(Path packRoot) {
        // 命名空间 → (枪包 → 该命名空间下的文件数)
        Map<String, Map<String, Integer>> weights = new LinkedHashMap<>();
        // 所有声明过命名空间的枪包（即使一个都没抢到也要保留按钮）
        Set<String> allPacks = new LinkedHashSet<>();

        if (packRoot != null && Files.isDirectory(packRoot)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(packRoot)) {
                for (Path entry : stream) {
                    String packName = entry.getFileName().toString();
                    Map<String, Integer> counts;
                    if (Files.isDirectory(entry)) {
                        counts = countDirectory(entry);
                    } else if (packName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                        packName = packName.substring(0, packName.length() - 4);
                        counts = countZip(entry);
                    } else {
                        continue;
                    }
                    if (counts.isEmpty()) {
                        continue;
                    }
                    allPacks.add(packName);
                    for (Map.Entry<String, Integer> count : counts.entrySet()) {
                        weights.computeIfAbsent(count.getKey(), key -> new LinkedHashMap<>())
                                .merge(packName, count.getValue(), Integer::sum);
                    }
                }
            } catch (IOException ignored) {
                // 读不到就当没有枪包，退回不筛选
            }
        }

        Map<String, Set<String>> packs = new LinkedHashMap<>();
        for (String packName : allPacks) {
            packs.put(packName, new LinkedHashSet<>());
        }
        Map<String, String> reverse = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : weights.entrySet()) {
            String winner = pickOwner(entry.getValue());
            if (winner == null) {
                continue;
            }
            packs.get(winner).add(entry.getKey());
            reverse.put(entry.getKey(), winner);
        }

        packToNamespaces = packs;
        namespaceToPack = reverse;
    }

    /** 文件数最多的包胜出；数量相同按包名排序，保证结果稳定。 */
    private static String pickOwner(Map<String, Integer> owners) {
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> owner : owners.entrySet()) {
            int count = owner.getValue();
            if (count > bestCount
                    || (count == bestCount && best != null
                    && owner.getKey().compareToIgnoreCase(best) < 0)) {
                best = owner.getKey();
                bestCount = count;
            }
        }
        return best;
    }

    /** 所有已安装枪包的名字（按名称排序），包括被覆盖光的。 */
    public static List<String> getPackNames() {
        if (packToNamespaces == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>(packToNamespaces.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** 某个枪包独占的全部命名空间。 */
    public static Set<String> getNamespacesOf(String packName) {
        if (packToNamespaces == null) {
            return Collections.emptySet();
        }
        return packToNamespaces.getOrDefault(packName, Collections.emptySet());
    }

    /** 某个命名空间归属哪个枪包；不认识则返回 null。 */
    public static String getOwnerOf(String namespace) {
        if (namespaceToPack == null) {
            return null;
        }
        return namespaceToPack.get(namespace);
    }

    /** 目录型枪包：统计 assets/&lt;ns&gt; 与 data/&lt;ns&gt; 下的文件数。 */
    private static Map<String, Integer> countDirectory(Path packDir) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String resourceRoot : RESOURCE_ROOTS) {
            Path dir = packDir.resolve(resourceRoot);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path child : stream) {
                    if (!Files.isDirectory(child)) {
                        continue;
                    }
                    counts.merge(child.getFileName().toString(), countFiles(child), Integer::sum);
                }
            } catch (IOException ignored) {
                // 单个枪包读失败不影响其他枪包
            }
        }
        return counts;
    }

    private static int countFiles(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return (int) walk.filter(Files::isRegularFile).limit(FILE_COUNT_CAP).count();
        } catch (IOException ignored) {
            return 0;
        }
    }

    /** zip 型枪包：统计 assets/&lt;ns&gt;/... 与 data/&lt;ns&gt;/... 的条目数。 */
    private static Map<String, Integer> countZip(Path zipPath) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String[] parts = entry.getName().split("/");
                if (parts.length < 3) {
                    continue;
                }
                if (!parts[0].equals("assets") && !parts[0].equals("data")) {
                    continue;
                }
                if (!parts[1].isEmpty()) {
                    counts.merge(parts[1], 1, Integer::sum);
                }
            }
        } catch (IOException ignored) {
            // 坏包直接跳过
        }
        return counts;
    }
}
