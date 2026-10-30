package demo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Test {
    //单线程池
    private static ExecutorService executorService = Executors.newSingleThreadExecutor();

    public static void fun() {
        ByteLRUCache byteLRUCache = new ByteLRUCache(512 * 1024 * 1024);
        executorService.execute(() -> {

        });
    }
}
