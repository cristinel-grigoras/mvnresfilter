package com.example;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

@WebServlet("/hello")
public class HelloServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/html;charset=UTF-8");
        try (PrintWriter out = resp.getWriter()) {
            out.println("<!DOCTYPE html>");
            out.println("<html><head><title>Simple WAR</title></head>");
            out.println("<body>");
            out.println("<h1>Hello from Simple WAR!</h1>");
            out.println("<p>Context: " + req.getContextPath() + "</p>");
            out.println("<p>Session timeout: " + req.getSession().getMaxInactiveInterval() + "s</p>");
            out.println("</body></html>");
        }
    }
}
