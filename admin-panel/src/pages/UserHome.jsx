import { useNavigate } from "react-router-dom";

export default function UserHome() {
    const navigate = useNavigate();
    const username = localStorage.getItem("username");

    const logout = () => {
        localStorage.clear();
        navigate("/login");
    };

    return (
        <div>
            <h2>Hoş geldin, {username}</h2>
            <button onClick={logout}>Çıkış Yap</button>
        </div>
    );
}