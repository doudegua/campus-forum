package net.doudegua.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
public class TestController {

    @GetMapping("/hello")
    public String test() {
        return """
                +------------------------------------------+
                |                                          |
                |      I   I  I---  |     |     O---O      |
                |      |---|  |---  |     |     |   |      |
                |      I   I  I---  I---  I---  O---O      |
                |                                          |
                +------------------------------------------+
                """;
    }
}
