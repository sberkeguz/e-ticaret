import { Routes, Route, Navigate } from "react-router-dom";
import Login from "./pages/Login";
import Register from "./pages/Register";
import UserHome from "./pages/UserHome";
import AdminPanel from "./pages/AdminPanel";
import ProtectedRoute from "./ProtectedRoute";

export default function App() {
    return (
        <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/register" element={<Register />} />

            {/* Sadece ADMIN girebilir */}
            <Route
                path="/admin"
                element={
                    <ProtectedRoute allowedRole="ADMIN">
                        <AdminPanel />
                    </ProtectedRoute>
                }
            />

            {/* Giriş yapmış herkes girebilir */}
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