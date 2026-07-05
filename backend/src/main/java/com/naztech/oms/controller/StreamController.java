package com.naztech.oms.controller;

import com.naztech.oms.service.StreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Server-Sent-Events stream consumed by the terminal (trades, order updates, ticks). */
@RestController
@RequestMapping("/api/stream")
@CrossOrigin(originPatterns = "*")
public class StreamController {

    private final StreamService stream;

    public StreamController(StreamService stream) { this.stream = stream; }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return stream.subscribe();
    }
}
