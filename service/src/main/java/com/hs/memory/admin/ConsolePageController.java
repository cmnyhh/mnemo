package com.hs.memory.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 控制台的真实地址是根路径；/console 只是让人少猜一次。 */
@Controller
public class ConsolePageController {

    @GetMapping("/console")
    public String console() {
        return "redirect:/";
    }
}
