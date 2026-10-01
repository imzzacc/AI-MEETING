import { Mic, Plus } from "lucide-react";
import { useTextareaAutosize } from "@/hooks/useTextareaAutosize";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";

export type SmartComposerViewProps = {
  value: string;
  onChange: (value: string) => void;
  onSend?: () => void;
  placeholder?: string;
  disabled?: boolean;
  showDefaultLeading?: boolean;
  showVoiceButton?: boolean;
  leading?: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
  isRecording?: boolean;
  isStarting?: boolean;
  audioError?: string | null;
  onMicClick?: (event: React.MouseEvent) => void;
};

export default function SmartComposerView({
  value,
  onChange,
  onSend,
  placeholder = "今天我能怎么帮助你？",
  disabled = false,
  showDefaultLeading = true,
  showVoiceButton = true,
  leading,
  actions,
  className,
  isRecording = false,
  isStarting = false,
  audioError,
  onMicClick,
}: SmartComposerViewProps) {
  const textareaRef = useTextareaAutosize(value);

  return (
    <div
      className={cn(
        "relative flex flex-col gap-2 rounded-[26px] border border-slate-200 bg-white shadow-sm transition-all hover:shadow-md focus-within:border-slate-300",
        className,
        isRecording && "border-slate-400",
      )}
    >
      <div className="flex w-full items-center px-4 pb-2 pt-4">
        <Textarea
          ref={textareaRef}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder={
            isStarting
              ? "正在连接语音识别..."
              : isRecording
                ? "正在倾听中..."
                : placeholder
          }
          rows={1}
          className="min-h-[44px] max-h-[200px] flex-1 resize-none border-0 bg-transparent px-0 py-2 text-lg shadow-none placeholder:text-slate-400 focus-visible:ring-0 focus-visible:ring-offset-0"
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey) {
              event.preventDefault();
              onSend?.();
            }
          }}
          disabled={disabled || isRecording || isStarting}
        />
      </div>

      <div className="flex items-center justify-between px-4 pb-3">
        <div className="flex items-center gap-2">
          {leading
            ? leading
            : showDefaultLeading && (
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-9 w-9 rounded-full text-slate-500 hover:bg-slate-100"
                  disabled={disabled || isRecording}
                  type="button"
                >
                  <Plus className="h-5 w-5" />
                </Button>
              )}
        </div>

        <div className="flex items-center gap-2">
          {actions}
          {showVoiceButton && (
            <Button
              variant={isRecording ? "destructive" : "ghost"}
              size="icon"
              className={cn(
                "h-9 w-9 rounded-full transition-all",
                isRecording
                  ? "animate-pulse bg-red-500 text-white hover:bg-red-600"
                  : "text-slate-500 hover:bg-slate-100",
              )}
              onClick={onMicClick}
              type="button"
              disabled={disabled}
              aria-label={
                isStarting
                  ? "取消语音连接"
                  : isRecording
                    ? "停止语音回答"
                    : "开始语音回答"
              }
              aria-pressed={isRecording}
            >
              <Mic className="h-5 w-5" />
            </Button>
          )}
        </div>
      </div>
      {audioError && (
        <p role="alert" className="px-4 pb-3 text-sm text-red-600">
          {audioError}
        </p>
      )}
    </div>
  );
}
