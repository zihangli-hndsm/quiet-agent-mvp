package app.quietagent;

import android.graphics.Bitmap;
import android.graphics.Rect;

import app.quietagent.receipt.ReceiptParser;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** OCR boundary backed by the Chinese model bundled in the APK. */
public interface ReceiptOcr extends Closeable {
    interface Cancellation { boolean isCancelled(); }

    final class Result {
        public final List<ReceiptParser.OcrLine> lines;
        public Result(List<ReceiptParser.OcrLine> lines) {
            this.lines = Collections.unmodifiableList(new ArrayList<ReceiptParser.OcrLine>(
                    lines == null ? Collections.<ReceiptParser.OcrLine>emptyList() : lines));
        }
    }

    Result recognize(Bitmap bitmap, String sourceName, Cancellation cancellation) throws IOException;

    static ReceiptOcr mlKitChinese() { return new BundledMlKitChinese(); }

    final class BundledMlKitChinese implements ReceiptOcr {
        private final TextRecognizer recognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());

        @Override public Result recognize(Bitmap bitmap, String sourceName, Cancellation cancellation) throws IOException {
            if (bitmap == null) throw new IOException("无法读取图片：" + sourceName);
            if (cancellation != null && cancellation.isCancelled()) throw new IOException("任务已取消");
            try {
                // A cancellation request takes effect after this single OCR call returns.
                Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)));
                if (cancellation != null && cancellation.isCancelled()) throw new IOException("任务已取消");
                List<ReceiptParser.OcrLine> lines = new ArrayList<ReceiptParser.OcrLine>();
                for (Text.TextBlock block : text.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        Rect rect = line.getBoundingBox();
                        ReceiptParser.Bounds bounds = rect == null
                                ? new ReceiptParser.Bounds(0, 0, 0, 0)
                                : new ReceiptParser.Bounds(rect.left, rect.top, rect.right, rect.bottom);
                        lines.add(new ReceiptParser.OcrLine(line.getText(), bounds));
                    }
                }
                return new Result(lines);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("任务已取消", error);
            } catch (ExecutionException error) {
                throw new IOException("中文 OCR 识别失败：" + sourceName, error.getCause());
            }
        }

        @Override public void close() { recognizer.close(); }
    }
}
