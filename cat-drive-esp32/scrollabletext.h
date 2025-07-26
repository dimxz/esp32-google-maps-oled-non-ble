#ifndef SCROLLABLETEXT_H
#define SCROLLABLETEXT_H

#define SCROLL_PAUSE_INTERVAL 700
#define SCROLL_LOOP_MARGIN 20
//#define ENABLE_BORDER

#include <U8g2_for_Adafruit_GFX.h>

class ScrollableText : public GFXcanvas1 {
public:
  enum class ScrollMode {
    LeftRight,
    Loop
  };

  enum class HorizontalAlignment {
    Left,
    Center,
    Right
  };

  String text() const {
    return mText;
  }

  ScrollableText(int16_t w, int16_t h)
    : GFXcanvas1(w, h) {
    mFontRenderer.begin(*this);
    setTextWrap(false);
  }

  void setScrollMode(ScrollMode mode) {
    if (mMode != mode)
      mConfigChanged = true;
    mMode = mode;
  }
  void setU8g2Font(const uint8_t *font) {
    mFontRenderer.setFont(font);
    mUseU8g2Renderer = true;
    mConfigChanged = true;
  }
  void setFont(const GFXfont *f) {
    Adafruit_GFX::setFont(f);
    mUseU8g2Renderer = false;
    mConfigChanged = true;
  }
  void setText(const String &text) {
    if (mText != text)
      mConfigChanged = true;
    mText = text;
  }
  /**
     * @brief Not sure about Arduino GFX text coordinate system,
     * sometime it draw upward from current cursor,
     * sometimes downward :(
     * See: https://learn.adafruit.com/adafruit-gfx-graphics-library/using-fonts
     * @param offset  Offset to add to y cursor
     */
  void setYOffset(int16_t offset) {
    if (mYOffset != offset)
      mConfigChanged = true;
    mYOffset = offset;
  }
  /**
     * @brief Only used when text is not scrolling
     */
  void setHorizontalAlignment(HorizontalAlignment alignment) {
    if (mTextHorizontalAlignment != alignment)
      mConfigChanged = true;
    mTextHorizontalAlignment = alignment;
  }
  void setScrollSteps(uint16_t steps) {
    mConfigChanged = mScrollSteps != steps;
    mScrollSteps = steps;
  }
  void setScrollInterval(uint16_t ms) {
    mConfigChanged = mScrollInterval != ms;
    mScrollInterval = ms;
    mConfigChanged = true;
  }

  void update() {
    if (mConfigChanged) {
      reset();
      mConfigChanged = false;
    }

    if (mMaxOffset == 0)
      return;

    const auto now = millis();

    if (mMode == ScrollMode::Loop) {
      if (now >= mWaitUntil_ms && mTextWidth > width()) {
        mWaitUntil_ms = now + mScrollInterval;

        mOffset -= mScrollSteps;
        if (mOffset < -mTextWidth)
          mOffset = 0;

        draw();
      }

      return;
    }

    switch (mStage) {
      case Stage::WaitForStart:
      case Stage::WaitForReturn:
        if (now >= mWaitUntil_ms)
          advanceStage();
        break;
      case Stage::RightToLeft:
      case Stage::LeftToRight:
        if (now >= mWaitUntil_ms) {
          mWaitUntil_ms = now + mScrollInterval;

          if (mStage == Stage::RightToLeft) {
            mOffset = constrain(mOffset - mScrollSteps, -mMaxOffset, 0);
            if (mOffset <= -mMaxOffset)
              advanceStage();
          } else if (mStage == Stage::LeftToRight) {
            mOffset = constrain(mOffset + mScrollSteps, -mMaxOffset, 0);
            if (mOffset >= 0)
              advanceStage();
          }

          draw();
        }
        break;
      default:
        break;
    }
  }

  void reset() {
    if (mUseU8g2Renderer) {
      mTextWidth = mFontRenderer.getUTF8Width(mText.c_str());
      mTextHeight = mFontRenderer.getFontAscent() - mFontRenderer.getFontDescent();
    } else {
      int16_t x, y;
      uint16_t w, h;
      getTextBounds(mText, 0, 0, &x, &y, &w, &h);
      mTextWidth = w;
      mTextHeight = h;
    }
    mMaxOffset = mTextWidth - width();
    if (mMaxOffset < 0)
      mMaxOffset = 0;

    mStage = Stage::WaitForStart;
    mWaitUntil_ms = millis() + SCROLL_PAUSE_INTERVAL;
    mOffset = 0;

    if (mMaxOffset == 0) {
      switch (mTextHorizontalAlignment) {
        case HorizontalAlignment::Left:
          break;
        case HorizontalAlignment::Center:
          mOffset = (width() - mTextWidth) / 2;
          break;
        case HorizontalAlignment::Right:
          mOffset = width() - mTextWidth;
          break;
      }
    }

    // add margin to Loop
    if (mMaxOffset > 0 && mMode == ScrollMode::Loop) {
      mTextWidth += SCROLL_LOOP_MARGIN;
    }

    draw();
  }

private:
  void advanceStage() {
    const auto now = millis();
    switch (mStage) {
      case Stage::WaitForStart:
        mStage = Stage::RightToLeft;
        mWaitUntil_ms = now + mScrollInterval;
        break;
      case Stage::RightToLeft:
        mStage = Stage::WaitForReturn;
        mWaitUntil_ms = now + SCROLL_PAUSE_INTERVAL;
        break;
      case Stage::WaitForReturn:
        mStage = Stage::LeftToRight;
        mWaitUntil_ms = now + mScrollInterval;
        break;
      case Stage::LeftToRight:
        mStage = Stage::WaitForStart;
        mWaitUntil_ms = now + SCROLL_PAUSE_INTERVAL;
        break;
      default:
        break;
    }
  }

  void draw() {
    fillScreen(0);

    auto x = mOffset;
    if (mMode == ScrollMode::Loop) {
      do {
        if (mUseU8g2Renderer) {
          mFontRenderer.drawUTF8(x, mTextHeight + mYOffset, mText.c_str());
        } else {
          setCursor(mOffset, mYOffset + (gfxFont ? (mTextHeight - 1) : 0));
          print(mText);
        }
        x += mTextWidth;
      } while (x < width() && mTextWidth > width());
    } else {
      if (mUseU8g2Renderer) {
        mFontRenderer.drawUTF8(mOffset, mTextHeight + mYOffset, mText.c_str());
      } else {
        // There are differences uses of cursor for builtin & new fonts
        // https://learn.adafruit.com/adafruit-gfx-graphics-library/using-fonts
        setCursor(mOffset, mYOffset + (gfxFont ? (mTextHeight - 1) : 0));
        print(mText);
      }
    }

    // setCursor(0, 20);
    // print(String(mStage) + " " + String(int(mOffset)) + " " + String(int(mMaxOffset)));
#ifdef ENABLE_BORDER
    drawRect(0, 0, width(), height(), 1);
#endif
  }

private:
  enum class Stage {
    WaitForStart,
    RightToLeft,
    WaitForReturn,
    LeftToRight,
  };

  U8G2_FOR_ADAFRUIT_GFX mFontRenderer{};
  uint32_t mWaitUntil_ms{};

  int16_t mOffset{};
  int16_t mMaxOffset{};
  int16_t mTextWidth{};
  int16_t mTextHeight{};
  int16_t mYOffset{ 0 };

  int16_t mScrollSteps{ 3 };
  uint16_t mScrollInterval{ 100 };

  ScrollMode mMode{};
  Stage mStage{};

  String mText{};
  HorizontalAlignment mTextHorizontalAlignment{};
  bool mUseU8g2Renderer{ false };
  bool mConfigChanged{ true };
};

#endif