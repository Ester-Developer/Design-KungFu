package com.kungfuchess.server;

import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates and tracks active {@link Room}s, keyed by a short human-shareable room code.
 */
public class RoomManager {

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I
    private static final int CODE_LENGTH = 4;

    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    // Separate counter so auto-matched (Play button) rooms get a distinct, obviously-internal id
    // shape and never collide with a human-typed room code.
    private final AtomicInteger matchedRoomCounter = new AtomicInteger(1);

    /** Creates a new room with a freshly generated, currently-unused code. */
    public Room createRoom() {
        String code;
        do {
            code = generateCode();
        } while (rooms.putIfAbsent(code, new Room(code)) != null);
        return rooms.get(code);
    }

    /** Creates a new room for a matchmaking pairing (not typed by hand, so uses an "M-" prefix). */
    public Room createMatchedRoom() {
        String code = "M-" + matchedRoomCounter.getAndIncrement();
        Room room = new Room(code);
        rooms.put(code, room);
        return room;
    }

    public Room getRoom(String roomId) {
        if (roomId == null) return null;
        return rooms.get(roomId.trim().toUpperCase());
    }

    public void removeRoom(String roomId) {
        if (roomId != null) {
            rooms.remove(roomId.trim().toUpperCase());
        }
    }

    /** All currently-tracked rooms (active games and rooms still waiting for a second player). */
    public java.util.Collection<Room> activeRooms() {
        return rooms.values();
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }
}
