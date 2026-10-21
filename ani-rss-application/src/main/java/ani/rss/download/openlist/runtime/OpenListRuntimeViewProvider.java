package ani.rss.download.openlist.runtime;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 为静态调度入口提供运行态适配器，避免调度器直接查询容器。
 * <p>
 * 未运行 Spring 时返回空实现，单元测试无需启动容器。
 */
@Component
public final class OpenListRuntimeViewProvider {
    private static final OpenListRuntimeView NONE = new OpenListRuntimeView() {
        @Override
        public String currentInfoHash() {
            return null;
        }

        @Override
        public void cancelCurrentOffline() {
        }

        @Override
        public Object offlineWaitSnapshot() {
            return null;
        }

        @Override
        public Object residualSnapshot() {
            return null;
        }

        @Override
        public Object tempDirResidualSnapshot() {
            return null;
        }
    };

    private static volatile OpenListRuntimeView view = NONE;

    public OpenListRuntimeViewProvider(ObjectProvider<OpenListRuntimeView> provider) {
        List<OpenListRuntimeView> views = provider.orderedStream().toList();
        view = views.stream()
                .filter(candidate -> candidate.getClass().getName().equals("ani.rss.download.OpenList"))
                .findFirst()
                .orElseGet(() -> views.isEmpty() ? NONE : views.get(0));
    }

    public static OpenListRuntimeView get() {
        return view;
    }
}
