import type { Metadata } from "next";
import "./globals.css";
import "./studio.css";
import "./phone.css";
export const metadata: Metadata = {
  title: "Tennis Vision Studio",
  description:
    "Visão computacional para tênis. Medições reais, confiança explícita.",
  icons: { icon: "/favicon.svg" },
};
export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="pt-BR">
      <body>{children}</body>
    </html>
  );
}
