package com.team.silveragent.application;

/**
 * 口语里的中文数字改写成阿拉伯数字，让数值解析认得。
 *
 * <p>老人报数值多数是读出来的：“我上压一百五”“血压九十八”“体温三十六点八”。
 * 解析器的数值正则只认 {@code [0-9]}，这些说法原来整个落空——而“记不上”对老人是实打实的摩擦，
 * 他得换个说法再说一遍。所以这里把连读的中文数字还原成数字，让后面的解析原样跑。
 *
 * <p>**只改写“敢确定是数字”的连读**，认不准就一个字都不动，宁可漏认也不认错：
 *
 * <ul>
 *   <li>连读里必须有一个数量级字（十/百/千/万）或者一个小数点，否则不当数字。
 *       “每周一三五早上八点吃药”里的“一三五”是星期几不是 135，“我住三楼”的“三”也不是数——
 *       它们都不带数量级，于是原样留着，交给备忘那套解析。</li>
 *   <li>“点”只有在后面紧跟数字时才算小数点（“三十六点八”＝36.8）；
 *       “三点半”“早上七点”里的“点”后面不是数字，不当小数点。</li>
 *   <li>口语尾巴按最常说的一档折算：“一百五”＝150、“三十五”＝35、“两千三”＝2300；
 *       带“零”的按字面来，“一百零五”＝105。</li>
 * </ul>
 *
 * <p>改写成数字之后，后面那张“数字后面跟这些字就不是数值”的表（点/分/月/日/岁/周/次…）
 * 照样生效，钟点和日期仍然进不了健康记录。
 */
public final class ChineseNumbers {
    private ChineseNumbers() { }

    private static final String DIGITS = "零〇一二两三四五六七八九";
    /** 与 {@link #DIGITS} 逐字对应：二和两都是 2。 */
    private static final String VALUES = "001223456789";
    private static final String MAGNITUDES = "十百千万";

    /** 把句子里敢确定的连读改写成数字；其余字符一个字不改。 */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        while (at < text.length()) {
            int end = runEnd(text, at);
            if (end > at) {
                String number = parse(text.substring(at, end));
                if (number != null) {
                    out.append(number);
                    at = end;
                    continue;
                }
            }
            out.append(text.charAt(at));
            at++;
        }
        return out.toString();
    }

    /** 从 at 开始的中文数字连读到哪儿结束；这里不是数字连读就返回 at（表示不动它）。 */
    private static int runEnd(String text, int at) {
        if (!isNumeral(text.charAt(at))) return at;
        boolean certain = false;
        int cursor = at;
        while (cursor < text.length()) {
            char ch = text.charAt(cursor);
            if (isNumeral(ch)) {
                if (MAGNITUDES.indexOf(ch) >= 0) certain = true;
                cursor++;
                continue;
            }
            if (ch == '点' && cursor + 1 < text.length() && isNumeral(text.charAt(cursor + 1))) {
                certain = true;
                cursor += 2;
                while (cursor < text.length() && isNumeral(text.charAt(cursor))) cursor++;
            }
            break;
        }
        return certain ? cursor : at;
    }

    /** “一百五”→"150"、“三十六点八”→"36.8"；不是规范说法返回 null（那就一个字都不动）。 */
    private static String parse(String run) {
        int dot = run.indexOf('点');
        Integer whole = parseWhole(dot < 0 ? run : run.substring(0, dot));
        if (whole == null) return null;
        if (dot < 0) return String.valueOf(whole);
        StringBuilder fraction = new StringBuilder();
        for (char ch : run.substring(dot + 1).toCharArray()) {
            int digit = digitOf(ch);
            if (digit < 0) return null;
            fraction.append(digit);
        }
        return whole + "." + fraction;
    }

    private static Integer parseWhole(String run) {
        if (run.isEmpty()) return null;
        long total = 0;
        long section = 0;
        int current = -1;
        int lastMagnitude = 0;
        boolean zeroSeen = false;
        for (char ch : run.toCharArray()) {
            int digit = digitOf(ch);
            if (digit >= 0) {
                if (digit == 0) zeroSeen = true;
                current = digit;
                continue;
            }
            int magnitude = magnitudeOf(ch);
            if (magnitude < 0) return null;
            if (magnitude == 10000) {
                long base = section + Integer.max(current, 0);
                total += (base == 0 ? 1 : base) * 10000L;
                section = 0;
                current = -1;
                lastMagnitude = 10000;
                zeroSeen = false;
                continue;
            }
            if (current < 0 && magnitude != 10) return null;   // “百三十五”不成话
            section += (long) Integer.max(current, 1) * magnitude;
            current = -1;
            lastMagnitude = magnitude;
            zeroSeen = false;
        }
        long value = total + section;
        if (current > 0) {
            value += zeroSeen || lastMagnitude <= 10 ? current : (long) current * (lastMagnitude / 10);
        }
        // 0 不是老人报的量；“亿”级也没人这么量
        return value <= 0 || value > 99999999L ? null : (int) value;
    }

    private static boolean isNumeral(char ch) {
        return digitOf(ch) >= 0 || magnitudeOf(ch) > 0;
    }

    private static int digitOf(char ch) {
        int at = DIGITS.indexOf(ch);
        return at < 0 ? -1 : VALUES.charAt(at) - '0';
    }

    private static int magnitudeOf(char ch) {
        return switch (ch) {
            case '十' -> 10;
            case '百' -> 100;
            case '千' -> 1000;
            case '万' -> 10000;
            default -> -1;
        };
    }
}
