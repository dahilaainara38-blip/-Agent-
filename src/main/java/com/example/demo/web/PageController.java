package com.example.demo.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/login")
    public String login() {
        return "forward:/login.html";
    }

    @GetMapping("/register")
    public String register() {
        return "forward:/register.html";
    }

    @GetMapping("/home")
    public String home() {
        return "forward:/home.html";
    }

    @GetMapping("/care")
    public String care() {
        return "forward:/care.html";
    }

    /** 旧聊天页已并入 Agent 工作台，保留重定向照顾历史入口。 */
    @GetMapping("/chat")
    public String chat() {
        return "redirect:/agent";
    }

    @GetMapping("/agent")
    public String agent() {
        return "forward:/agent.html";
    }

    @GetMapping("/shop")
    public String shop() {
        return "forward:/shop.html";
    }

    @GetMapping("/shop-publish")
    public String shopPublish() {
        return "forward:/shop-publish.html";
    }
}
