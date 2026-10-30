package demo;

import java.util.HashMap;
import java.util.Map;

//实现一个按总字节数限制容量的 LRU 缓存
public class ByteLRUCache {
    private long capacity;
    private long curSize;
    private Map<String, Node> nodeMap = new HashMap<>();
    private Node head = new Node(null, null);
    private Node tail = new Node(null, null);
    private final class Node {
        String key;
        byte[] value;
        Node prev,next;
        Node(String key, byte[] value) {
            this.key = key;
            this.value = value;
        }
    }
    public ByteLRUCache(long capacity) {
        this.capacity = capacity;
        head.next = tail;
        tail.prev = head;
    }

    public byte[] get(String key) {
        if (nodeMap.containsKey(key)) {
            Node node = nodeMap.get(key);
            unLink(node);
            addFirst(node);
            return node.value;
        }
        return null;
    }

    public void put(String key, byte[] value) {
        Node newNode = new Node(key, value);
        Node oldNode = nodeMap.get(key);
        if(oldNode != null) {
            remove(oldNode);
        }
        while (curSize + value.length > capacity) {
            remove(tail.prev);
        }
        addFirst(newNode);
        nodeMap.put(key, newNode);
        curSize += value.length;
    }

    private void remove(Node node) {
        unLink(node);
        nodeMap.remove(node.key);
        curSize -= node.value.length;
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
