import { useState, useEffect } from 'react'
import axios from 'axios'
import './App.css'

function App() {
    const [username, setUsername] = useState('')
    const [password, setPassword] = useState('')
    const [message, setMessage] = useState('')
    // Kullanıcının giriş yapıp yapmadığını takip eden state
    const [isLoggedIn, setIsLoggedIn] = useState(false)

    // Sayfa yüklendiğinde veya yenilendiğinde hafızada token var mı diye kontrol et
    useEffect(() => {
        const token = localStorage.getItem('token');
        if (token) {
            setIsLoggedIn(true);
        }
    }, []);

    const handleLogin = async (e) => {
        e.preventDefault()
        try {
            const response = await axios.post('http://localhost:8080/api/auth/login', {
                username: username,
                password: password
            })

            // Token'ı tarayıcının hafızasına kaydet (Backend'in gönderdiği token anahtarını alıyoruz)
            const token = response.data.token;
            localStorage.setItem('token', token);

            setIsLoggedIn(true);
            setMessage('');
        } catch (error) {
            setMessage('Giriş başarısız! Bilgileri kontrol edin.')
            console.error("Hata Detayı:", error)
        }
    }

    const handleLogout = () => {
        // Çıkış yapıldığında token'ı sil ve ana ekrana dön
        localStorage.removeItem('token');
        setIsLoggedIn(false);
        setUsername('');
        setPassword('');
    }

    // ==========================================
    // EĞER GİRİŞ YAPILDIYSA BU EKRANI GÖSTER
    // ==========================================
    if (isLoggedIn) {
        return (
            <div style={{ padding: '50px', fontFamily: 'sans-serif' }}>
                <h2>Admin Paneline Hoş Geldin!</h2>
                <p>Sisteme başarıyla bağlandın ve yetki belgen (Token) hafızaya alındı.</p>
                <button onClick={handleLogout} style={{ padding: '10px', backgroundColor: '#dc3545', color: 'white', border: 'none', borderRadius: '5px', cursor: 'pointer' }}>
                    Çıkış Yap
                </button>
            </div>
        )
    }

    // ==========================================
    // EĞER GİRİŞ YAPILMADIYSA GİRİŞ FORMUNU GÖSTER
    // ==========================================
    return (
        <div style={{ padding: '50px', fontFamily: 'sans-serif' }}>
            <h2>Admin Paneli Girişi</h2>
            <form onSubmit={handleLogin} style={{ display: 'flex', flexDirection: 'column', width: '300px', gap: '15px' }}>
                <input
                    type="text"
                    placeholder="Kullanıcı Adı"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    style={{ padding: '10px', fontSize: '16px' }}
                />
                <input
                    type="password"
                    placeholder="Şifre"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    style={{ padding: '10px', fontSize: '16px' }}
                />
                <button type="submit" style={{ padding: '10px', fontSize: '16px', cursor: 'pointer', backgroundColor: '#007BFF', color: 'white', border: 'none', borderRadius: '5px' }}>
                    Giriş Yap
                </button>
            </form>
            <p style={{ marginTop: '20px', fontWeight: 'bold', color: 'red' }}>
                {message}
            </p>
        </div>
    )
}

export default App