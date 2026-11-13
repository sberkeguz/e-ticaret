import { Navigate } from "react-router-dom";
import { isLoggedIn } from "./session";
import type { ReactNode } from "react";
import type { Role } from "./types/api";

export default function ProtectedRoute({ children, allowedRole }: { children: ReactNode; allowedRole?: Role }) {
    const role = localStorage.getItem("role");

    // Giriş yapılmamış
    if (!isLoggedIn()) return <Navigate to="/login" replace />;

    // Rol uymuyor (ör. USER, /admin'e girmeye çalışıyor)
    if (allowedRole && role !== allowedRole) return <Navigate to="/home" replace />;

    return children;
}