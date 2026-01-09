package fun.ceroxe.api.wechatpay;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import net.sourceforge.tess4j.util.ImageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信支付 VNC 专用监控守卫
 * 特性：空间语义分析、防历史记录误判、防重复触发
 */
public class WeChatPayWatcher {

    private static final Logger logger = LoggerFactory.getLogger(WeChatPayWatcher.class);

    // Tesseract 数据路径 (根据你的 Debian 安装位置调整)
    private static final String TESS_DATA_PATH = "/usr/share/tesseract-ocr/4.00/tessdata";

    // 配置：允许的时间误差（分钟）
    private static final int TIME_TOLERANCE_MINUTES = 2;
    private final Tesseract tesseract;
    private final Robot robot;
    // 状态防抖：记录上一笔成功处理的交易签名 (Time + Amount)
    private String lastProcessedTransactionSignature = "";

    public WeChatPayWatcher() throws AWTException {
        // 必须设置，否则 VNC 下可能会报错
        System.setProperty("java.awt.headless", "false");

        this.robot = new Robot();
        this.tesseract = new Tesseract();
        this.tesseract.setDatapath(TESS_DATA_PATH);
        this.tesseract.setLanguage("chi_sim"); // 中文库
        // 设置 OCR 引擎模式为 LSTM (更准)
        this.tesseract.setOcrEngineMode(1);
    }
    /**
     * 核心阻塞 API
     *
     * @param targetAmount 期待收款金额 (例如 8.00)
     * @return true 仅当检测到【新鲜】且【金额匹配】的收款
     */
    public boolean awaitNewPayment(double targetAmount) {
        logger.info("🛡️ 守卫启动 | 监听金额: {}", targetAmount);

        String targetAmountStr = String.format("%.2f", targetAmount); // "8.00"

        while (true) {
            try {
                // 1. 截屏
                Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
                BufferedImage originalImage = robot.createScreenCapture(screenRect);

                // 2. 图像增强 (这对 VNC 截图至关重要，解决字体发虚问题)
                // 转灰度 -> 二值化，让文字极黑，背景极白
                BufferedImage processedImage = ImageHelper.convertImageToGrayscale(originalImage);

                // 3. 获取所有单词及其坐标 (核心步骤)
                List<Word> words = tesseract.getWords(processedImage, ITessAPI.TessPageIteratorLevel.RIL_WORD);

                // 4. 分析逻辑
                if (analyzeScreen(words, targetAmountStr)) {
                    return true;
                }

                // 避免 CPU 100%，休眠 1 秒
                Thread.sleep(1000);

            } catch (Exception e) {
                logger.error("扫描异常 (通常可忽略): {}", e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ex) {
                }
            }
        }
    }

    /**
     * 空间语义分析引擎 (加强版)
     * 特性：完全忽略屏幕右上角系统时间变化，只锁定收款卡片区域
     */
    private boolean analyzeScreen(List<Word> words, String targetAmountStr) {
        // Step A: 找到所有可能是目标金额的单词块
        List<Word> amountCandidates = words.stream()
                .filter(w -> w.getText().contains(targetAmountStr))
                .toList();

        if (amountCandidates.isEmpty()) {
            return false;
        }

        // 遍历每一个找到的 "8.00"
        for (Word amountWord : amountCandidates) {
            Rectangle amountRect = amountWord.getBoundingBox();

            // Step B: 向上寻找“最近的”时间/日期标签 (语义绑定)
            Word nearestTimeLabel = findNearestHeader(words, amountRect);

            if (nearestTimeLabel == null) {
                continue;
            }

            String timeText = nearestTimeLabel.getText().trim();

            // --- 关键修改：生成“交易指纹” ---
            // 只有当 (时间 + 金额) 这个组合改变时，我们才视为新状态
            // 这样右上角系统时间怎么跳，都不影响这里的判断
            String currentTransactionSignature = timeText + "_" + targetAmountStr;

            // 1. 状态防抖：如果这个指纹刚才已经处理过了，直接跳过
            if (currentTransactionSignature.equals(lastProcessedTransactionSignature)) {
                // 这是一个已知的（无论是成功的还是失败的）状态，不再重复打印日志，不再重复计算
                continue;
            }

            logger.debug("🔍 捕获到新状态: 金额 {}, 时间标签: [{}]", targetAmountStr, timeText);

            // 2. 历史关键词查杀
            if (isHistoricalRecord(timeText)) {
                // 标记这个“历史记录”为已处理，防止下一轮循环一直报 Warning
                lastProcessedTransactionSignature = currentTransactionSignature;
                logger.warn("❌ 忽略历史记录: {}", currentTransactionSignature);
                continue;
            }

            // 3. 时间新鲜度校验
            if (isTimeFresh(timeText)) {
                logger.info("✅✅✅ 支付验证通过! 正在执行业务逻辑... | 指纹: {}", currentTransactionSignature);

                // 记录下来，防止这笔 8.00 元在接下来几分钟内被重复触发
                lastProcessedTransactionSignature = currentTransactionSignature;
                return true;
            } else {
                // 如果时间不新鲜（比如是 10 分钟前的），也记录下来，避免重复校验
                lastProcessedTransactionSignature = currentTransactionSignature;
            }
        }

        return false;
    }

    /**
     * 寻找指定区域上方最近的“时间/日期”特征词
     */
    private Word findNearestHeader(List<Word> allWords, Rectangle amountRect) {
        Word bestMatch = null;
        double minDistance = Double.MAX_VALUE;

        for (Word word : allWords) {
            Rectangle r = word.getBoundingBox();
            String text = word.getText();

            // 1. 必须在金额上方 (r.y < amountRect.y)
            // 2. 垂直距离不能太远 (比如超过 400px 可能就是上一个气泡了)
            if (r.y < amountRect.y && (amountRect.y - r.y) < 400) {

                // 3. 必须包含时间特征 (Yesterday, :, 昨天, 今天, 刚刚)
                if (hasTimeFeature(text)) {
                    // 计算中心点距离
                    double dist = Math.sqrt(Math.pow(r.getCenterX() - amountRect.getCenterX(), 2)
                            + Math.pow(r.getCenterY() - amountRect.getCenterY(), 2));

                    if (dist < minDistance) {
                        minDistance = dist;
                        bestMatch = word;
                    }
                }
            }
        }
        return bestMatch;
    }

    /**
     * 判断文本是否包含时间特征
     */
    private boolean hasTimeFeature(String text) {
        return text.contains(":") ||
                text.contains("Yesterday") || text.contains("昨天") ||
                text.contains("Today") || text.contains("今天") ||
                text.matches(".*\\d{1,2}:\\d{2}.*");
    }

    /**
     * 判断是否是明确的历史记录
     */
    private boolean isHistoricalRecord(String text) {
        return text.contains("Yesterday") ||
                text.contains("昨天") ||
                text.contains("Monday") || text.contains("Tuesday") ||
                text.contains("Wednesday") || text.contains("Thursday") ||
                text.contains("Friday") || text.contains("Saturday") ||
                text.contains("Sunday") ||
                text.contains("-"); // 日期格式 2023-01-01
    }

    /**
     * 校验时间是否在允许误差范围内 (比如 2分钟)
     */
    private boolean isTimeFresh(String timeText) {
        // 如果 OCR 识别出 "Today" 或 "今天" 或 "刚刚"，直接放行 (视为极短时间内)
        if (timeText.contains("Today") || timeText.contains("今天") || timeText.contains("刚刚")) {
            return true;
        }

        // 提取 HH:mm
        Pattern p = Pattern.compile("(\\d{1,2}:\\d{2})");
        Matcher m = p.matcher(timeText);

        if (m.find()) {
            String timePart = m.group(1);
            try {
                // 补全前导零 9:00 -> 09:00
                if (timePart.length() == 4) timePart = "0" + timePart;

                LocalTime txnTime = LocalTime.parse(timePart, DateTimeFormatter.ofPattern("HH:mm"));
                LocalTime now = LocalTime.now();

                // 计算分钟差
                long diff = Math.abs(ChronoUnit.MINUTES.between(txnTime, now));

                // 处理跨天边界 (比如 23:59 vs 00:01)，这里简化处理，认为差值极大也是一种跨天
                // 正常逻辑：误差 <= tolerance
                if (diff <= TIME_TOLERANCE_MINUTES) {
                    return true;
                } else {
                    logger.warn("时间校验失败: 识别时间 {}, 当前时间 {}, 误差 {} 分钟", txnTime, now, diff);
                }
            } catch (Exception e) {
                // 解析失败，保守起见返回 false
            }
        }
        return false;
    }
}