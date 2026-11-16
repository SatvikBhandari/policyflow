package com.policyflow.policyflow.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.policyflow.policyflow.security.AuthenticatedUser;
import com.policyflow.policyflow.security.RoleLanding;

@Controller
public class AuthController {

    @GetMapping("/login")
    public String loginPage(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user != null) {
            return "redirect:" + RoleLanding.urlFor(user.getRole());
        }
        return "login";
    }
}