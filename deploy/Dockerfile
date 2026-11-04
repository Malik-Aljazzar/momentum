# Momentum backend (Quart) behind the host's nginx.
FROM python:3.12-slim
WORKDIR /app
# the content filter's image model (server/nsfw_model.py: pinned, hash-checked) - its own layer, kept between deploys
COPY server/nsfw_model.py /opt/models/nsfw_model.py
RUN python /opt/models/nsfw_model.py /opt/models
ENV MOMENTUM_NSFW_MODEL=/opt/models/nsfw_image_detection_int8.onnx
COPY server/requirements.txt server/requirements.txt
RUN pip install --no-cache-dir -r server/requirements.txt hypercorn
COPY server server
COPY pixelart/out pixelart/out
COPY web/dist web/dist
ENV MOMENTUM_DB=sqlite+aiosqlite:////data/momentum.db \
    PYTHONUNBUFFERED=1
EXPOSE 8000
CMD ["hypercorn", "server.app:create_app()", "--bind", "0.0.0.0:8000", "--workers", "1"]
