package com.example.src.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Catch-all controller for client-side SPA routes.
 *
 * Any GET request that is not matched by a more specific @RestController
 * (i.e. not under /api/**) is forwarded to index.html so that React Router
 * can handle the route on the client side.
 *
 * This prevents Spring from returning 404/500 when a user refreshes the
 * browser on a deep SPA path such as /login, /match, or /admin/patterns.
 */
@Controller
public class SpaController {

    @RequestMapping(value = {
        "/",
        "/login",
        "/match",
        "/forbidden",
        "/admin",
        "/admin/**"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
