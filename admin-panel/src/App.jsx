import { Routes, Route, Navigate } from "react-router-dom";
import Login from "./pages/Login";
import Register from "./pages/Register";
import UserHome from "./pages/UserHome";
import ProtectedRoute from "./ProtectedRoute";
import AdminPanel from "./pages/AdminPanel";
import AdminBrand from "./pages/AdminBrand";
import AdminUsers from "./pages/AdminUsers";
import AdminCategory from "./pages/AdminCategory";

export default function App() {
    return (
        <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/register" element={<Register />} />

            <Route
                path="/admin"
                element={
                    <ProtectedRoute allowedRole="ADMIN">
                        <AdminPanel />
                    </ProtectedRoute>
                }
            />

            <Route
                path="/admin/brand"
                element={
                    <ProtectedRoute allowedRole="ADMIN">
                        <AdminBrand />
                    </ProtectedRoute>
                }
            />

            {/* Kategori yönetimi - sadece ADMIN */}
            <Route
                path="/admin/category"
                element={
                    <ProtectedRoute allowedRole="ADMIN">
                        <AdminCategory />
                    </ProtectedRoute>
                }
            />

            <Route
                path="/admin/users"
                element={
                    <ProtectedRoute allowedRole="ADMIN">
                        <AdminUsers />
                    </ProtectedRoute>
                }
            />

            <Route
                path="/home"
                element={
                    <ProtectedRoute>
                        <UserHome />
                    </ProtectedRoute>
                }
            />

            <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
    );
}