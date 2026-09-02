// Command ts9-stream 是 TeamSpeak9 的旁挂屏幕共享服务：提供 TSSP v1 信令与 SFU 媒体转发。
//
// 本服务与官方 tsserver 并行部署，仅通过公开的 ServerQuery 接口做只读身份校验，
// 不修改、不代理、不逆向 tsserver 的任何流量。
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/pion/webrtc/v4"

	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/auth"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/config"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/logx"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/sfu"
	"github.com/MoonCC233/TeamSpeak9/server/ts9-stream/internal/signaling"
)

// version 在构建时可通过 -ldflags "-X main.version=..." 覆盖。
var version = "0.1.0-dev"

func main() {
	var (
		cfgPath     = flag.String("config", "", "配置文件路径（YAML）")
		showVersion = flag.Bool("version", false, "打印版本后退出")
		checkOnly   = flag.Bool("check", false, "只校验配置与 ServerQuery 连通性后退出")
	)
	flag.Parse()

	if *showVersion {
		fmt.Println("ts9-stream", version)
		return
	}

	if err := run(*cfgPath, *checkOnly); err != nil {
		fmt.Fprintln(os.Stderr, "启动失败:", err)
		os.Exit(1)
	}
}

func run(cfgPath string, checkOnly bool) error {
	cfg, err := config.Load(cfgPath)
	if err != nil {
		return err
	}

	log := logx.New(cfg.Log.Level, cfg.Log.Format)
	log.Info("ts9-stream 启动中", "version", version, "config", cfgPath)

	if cfg.Runtime.DevInsecure {
		log.Warn("已启用 runtime.dev_insecure：使用明文 ws:// 且可能自动生成令牌密钥，切勿用于生产环境")
	}
	if cfg.Auth.GeneratedSecret {
		log.Warn("auth.token_secret 未配置，已生成临时随机密钥；重启后所有令牌失效")
	}
	if !cfg.TLSEnabled() {
		log.Warn("未配置 TLS，客户端必须使用 ws:// 连接；生产环境请配置 listen.tls_cert 与 listen.tls_key")
	}

	verifier := auth.NewVerifier(&cfg, log)
	defer verifier.Close()

	signer, err := auth.NewSigner(cfg.Auth.TokenSecret, cfg.Auth.TokenTTL)
	if err != nil {
		return err
	}

	engine, err := sfu.New(sfu.Config{
		ICEServers:  buildICEServers(&cfg),
		UDPPortMin:  cfg.Media.UDPPortMin,
		UDPPortMax:  cfg.Media.UDPPortMax,
		PublicIP:    cfg.Media.PublicIP,
		VideoCodecs: cfg.Media.VideoCodecs,
		AudioCodecs: cfg.Media.AudioCodecs,
		PLIInterval: cfg.Media.PLIInterval,
	}, log)
	if err != nil {
		return fmt.Errorf("初始化 SFU 失败: %w", err)
	}
	defer engine.Close()

	if checkOnly {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		if err := verifier.HealthCheck(ctx); err != nil {
			return err
		}
		log.Info("配置与 ServerQuery 连通性校验通过", "servers", len(cfg.Servers))
		return nil
	}

	hub := signaling.NewHub(signaling.Deps{
		Config:   &cfg,
		Log:      log,
		Signer:   signer,
		Verifier: verifier,
		Engine:   engine,
	})

	mux := http.NewServeMux()
	mux.Handle(cfg.Listen.BasePath, hub)
	mux.HandleFunc("/healthz", healthHandler(hub, verifier))
	mux.HandleFunc("/readyz", readyHandler(verifier))

	srv := &http.Server{
		Addr:              cfg.Listen.Addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		// WebSocket 是长连接，不能设置 WriteTimeout/ReadTimeout，
		// 空闲检测由信令层的 ping 与 read deadline 负责。
		IdleTimeout: 0,
	}

	errCh := make(chan error, 1)
	go func() {
		scheme := "ws"
		if cfg.TLSEnabled() {
			scheme = "wss"
		}
		log.Info("开始监听",
			"addr", cfg.Listen.Addr,
			"endpoint", scheme+"://<host>"+cfg.Listen.BasePath,
			"modes", strings.Join(cfg.ModeStrings(), ","),
			"servers", len(cfg.Servers),
		)
		var serveErr error
		if cfg.TLSEnabled() {
			serveErr = srv.ListenAndServeTLS(cfg.Listen.TLSCert, cfg.Listen.TLSKey)
		} else {
			serveErr = srv.ListenAndServe()
		}
		if serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
			errCh <- serveErr
			return
		}
		errCh <- nil
	}()

	// 启动后异步做一次 ServerQuery 自检，失败只告警不阻止启动，
	// 避免 tsserver 稍晚启动时本服务直接退出。
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		if err := verifier.HealthCheck(ctx); err != nil {
			log.Warn("ServerQuery 自检未通过，鉴权将不可用直至恢复", "err", err)
			return
		}
		log.Info("ServerQuery 自检通过")
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)

	select {
	case err := <-errCh:
		if err != nil {
			return fmt.Errorf("监听失败: %w", err)
		}
		return nil
	case sig := <-sigCh:
		log.Info("收到退出信号，开始优雅关闭", "signal", sig.String())
	}

	ctx, cancel := context.WithTimeout(context.Background(), cfg.Runtime.ShutdownGrace)
	defer cancel()

	hub.Shutdown(ctx)
	if err := srv.Shutdown(ctx); err != nil {
		log.Warn("HTTP 服务关闭超时，强制结束", "err", err)
		_ = srv.Close()
	}
	log.Info("已退出")
	return nil
}

// buildICEServers 把配置里的 STUN/TURN 转成 pion 用的形式，供 SFU 自身收集候选。
func buildICEServers(cfg *config.Config) []webrtc.ICEServer {
	out := make([]webrtc.ICEServer, 0, 2)
	if len(cfg.ICE.STUNURLs) > 0 {
		out = append(out, webrtc.ICEServer{URLs: append([]string(nil), cfg.ICE.STUNURLs...)})
	}
	// SFU 作为服务端通常有公网可达地址，不需要 TURN 中继；
	// TURN 只下发给客户端（见 auth.ICEProvider）。
	return out
}

func healthHandler(hub *signaling.Hub, _ *auth.Verifier) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusOK)
		fmt.Fprintf(w, `{"status":"ok","version":%q,"sessions":%d,"streams":%d}`+"\n",
			version, hub.SessionCount(), hub.StreamCount())
	}
}

func readyHandler(verifier *auth.Verifier) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		if err := verifier.HealthCheck(ctx); err != nil {
			w.WriteHeader(http.StatusServiceUnavailable)
			fmt.Fprintf(w, `{"status":"degraded","error":%q}`+"\n", err.Error())
			return
		}
		w.WriteHeader(http.StatusOK)
		fmt.Fprintln(w, `{"status":"ready"}`)
	}
}
