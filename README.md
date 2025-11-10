
---

# explanation of behavior

- **LOGIN flow**: first line must be `LOGIN <username>`. Server replies with `OK` or `ERR username-taken`.
- **MSG**: after login, send `MSG <text>`. Server broadcasts `MSG <username> <text>` to *all* clients (including sender).
- **WHO**: returns multiple lines `USER <username>` — one per connected user.
- **DM**: `DM <username> <text>` — server sends `DM <from> <text>` to the target (and replies `OK` to sender).
- **PING**: replies `PONG`.
- **Disconnect**: when client closes connection (or is timed out), server broadcasts `INFO <username> disconnected`.
- **Idle timeout**: 60 seconds since last received line will cause server to disconnect that client.

---

