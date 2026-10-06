import { Navigate } from "react-router-dom";

export default function ProtectedRoute({ children, allowedRole }) {
    const token = localStorage.getItem("token");
    const role = localStorage.getItem("role");

    // Giriş yapılmamış
    if (!token) return <Navigate to="/login" replace />;

    // Rol uymuyor (ör. USER, /admin'e girmeye çalışıyor)
    if (allowedRole && role !== allowedRole) return <Navigate to="/home" replace />;

    return children;
}