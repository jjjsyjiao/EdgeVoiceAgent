package demo;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class FileByteLRUCache {
    private static final long DEFAULT_CACHE_SIZE = 512 * 1024 * 1024;
    private long cacheSize = DEFAULT_CACHE_SIZE;
    private long curSize;
    private String cachePath;
    private Map<String, Node> cacheMap = new HashMap<>();
    private Node head = new Node(null);
    private Node tail = new Node(null);

    public FileByteLRUCache(String cachePath) {
        this.cachePath = cachePath;
        init();
        head.next = tail;
        tail.prev = head;
    }

    public void setCacheSize(long size) {
        this.cacheSize = size;
    }

    private void init() {
        File cacheDir = new File(cachePath);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        for (File file : cacheDir.listFiles()) {
            cacheMap.put(file.getName(), new Node(file.getName()));
            curSize += file.length();
        }
    }

    private class Node{
        String key;

        Node prev, next;

        public Node(String key) {
            this.key = key;
        }
    }

    public void get(String key) {
        if (cacheMap.containsKey(key)) {
            Node node = cacheMap.get(key);
            unLink(node);
            addFirst(node);
        }
    }

    private void addFirst(Node node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void unLink(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

}
