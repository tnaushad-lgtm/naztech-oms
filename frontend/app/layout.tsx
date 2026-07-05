import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Naztech OMS — Exchange-Hosted Order Management",
  description: "Exchange-Hosted OMS for DSE / CSE — Naztech Securities",
};

const themeScript = `(function(){try{var t=localStorage.getItem('oms_theme')||'midnight';document.documentElement.setAttribute('data-theme',t);}catch(e){}})();`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" data-theme="midnight">
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
      </head>
      <body>{children}</body>
    </html>
  );
}
